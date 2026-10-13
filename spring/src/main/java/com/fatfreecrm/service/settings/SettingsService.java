package com.fatfreecrm.service.settings;

import org.springframework.beans.factory.annotation.Autowired;
import com.fatfreecrm.config.SettingsProperties;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.repository.SettingRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * AB-273 (settings-i18n): read-only port of Rails {@code Setting[]} tier resolution
 * (app/models/setting.rb:55-65):
 *
 * <ol>
 *   <li>resolved-value memo (replaces Rails' per-request {@code cache}; the Rails cache is cleared
 *       every request via application_controller.rb:109-111, ours is bounded by the DB snapshot TTL),</li>
 *   <li>DB tier: a snapshot of the whole {@code settings} table keyed by name, lowest id winning on
 *       duplicate names, each value decoded by {@link SettingValueCodec}. A row whose decoded value is
 *       Ruby-{@code blank?} (null, false, "", whitespace-only, empty list, empty map) falls through to
 *       the YAML tier — mirroring {@code setting.rb:61} {@code value.present?}; any other value
 *       REPLACES the YAML value wholesale (no merge),</li>
 *   <li>YAML tier: {@code settings.default.yml} plus an optional overrides file deep-merged with
 *       ActiveSupport semantics (setting.rb:114-117: both values Hash &rarr; recurse; otherwise the
 *       later value replaces, including nil/false; arrays are replaced, never concatenated).</li>
 * </ol>
 *
 * <p>Normalised value model: map keys at every level have a leading ':' stripped (indifferent
 * access, mirroring {@code HashWithIndifferentAccess}); values are unchanged (symbols stay
 * {@code ":name"} strings). YAML tiers are loaded once at startup; an override file containing
 * {@code <%} fails fast because Spring has no ERB. A configured overrides path that does not exist
 * is ignored like Rails' {@code File.exist?} check.
 *
 * <p>The DB snapshot is refreshed lazily when older than {@code cacheTtl} (documented staleness
 * bound; volatile immutable snapshot + synchronized refresh; inject {@link Clock} in tests). There
 * is intentionally no write API in this track: Rails remains the writer of {@code settings} rows.
 */
@Service
public class SettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    private final SettingRepository settingRepository;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<String, Object> yaml;

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Instant.MIN, new ConcurrentHashMap<>());

    @Autowired
    public SettingsService(SettingsProperties properties, SettingRepository settingRepository,
        ResourceLoader resourceLoader, Clock clock) {
        this(
            loadTier(resourceLoader, properties.defaults(), true),
            loadTier(resourceLoader, properties.overrides(), false),
            settingRepository, clock, properties.cacheTtl());
    }

    SettingsService(Map<String, Object> defaults, Map<String, Object> overrides,
        SettingRepository settingRepository, Clock clock, Duration cacheTtl) {
        this.settingRepository = settingRepository;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
        Map<String, Object> merged = deepMerge(defaults, overrides);
        this.yaml = merged;
    }

    private static final Object NULL = new Object();

    public Object get(String key) {
        Snapshot current = snapshot();
        Object cached = current.memo.get(key);
        if (cached != null) {
            return cached == NULL ? null : cached;
        }
        if (current.values.containsKey(key)) {
            Object value = current.values.get(key);
            if (isPresent(value)) {
                current.memo.put(key, value);
                return value;
            }
        }
        Object value = yaml.containsKey(key) ? yaml.get(key) : null;
        current.memo.put(key, value == null ? NULL : value);
        return value;
    }

    public Optional<String> string(String key) {
        return Optional.ofNullable(get(key)).map(String::valueOf);
    }

    /** Ruby truthiness: everything except null/false is truthy. */
    public boolean truthy(String key) {
        Object value = get(key);
        return value != null && !Boolean.FALSE.equals(value);
    }

    /** List elements with a leading ':' stripped; non-list values yield an empty list. */
    public List<String> symbolList(String key) {
        Object value = get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            String text = String.valueOf(element);
            result.add(text.startsWith(":") ? text.substring(1) : text);
        }
        return result;
    }

    /**
     * Rails {@code Setting.dig} (setting.rb:91-100): nil or no remaining path returns the value;
     * a Map digs further; any other intermediate raises, mirroring {@code TypeError}.
     */
    public Object dig(String key, String... path) {
        Object value = get(key);
        if (value == null || path.length == 0) {
            return value;
        }
        Object current = value;
        for (String step : path) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(step);
            } else {
                throw new IllegalArgumentException(current.getClass().getSimpleName() + " does not have #dig method");
            }
        }
        return current;
    }

    /** Drops the current snapshot; the next read reloads the DB tier and re-memoizes. */
    public void evict() {
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        if (current != null && Duration.between(current.loadedAt, clock.instant()).compareTo(cacheTtl) < 0) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && Duration.between(current.loadedAt, clock.instant()).compareTo(cacheTtl) < 0) {
                return current;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (Setting row : settingRepository.findAll(Sort.by("id"))) {
                if (values.containsKey(row.getName())) {
                    continue;
                }
                Object decoded;
                try {
                    decoded = SettingValueCodec.decode(row.getValue());
                } catch (RuntimeException exception) {
                    LOG.warn("Skipping undecodable settings row name={} id={}", row.getName(), row.getId());
                    continue;
                }
                values.put(row.getName(), normalizeValue(decoded));
            }
            snapshot = new Snapshot(Collections.unmodifiableMap(values), clock.instant(), new ConcurrentHashMap<>());
            return snapshot;
        }
    }

    /** Ruby {@code blank?}: nil, false, "", whitespace-only, empty list, empty map are blank. */
    private static boolean isPresent(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTier(ResourceLoader resourceLoader, String location, boolean required) {
        if (location == null || location.isBlank()) {
            return Map.of();
        }
        try {
            String text;
            if (location.startsWith("classpath:")) {
                Resource resource = resourceLoader.getResource(location);
                try (InputStream input = resource.getInputStream()) {
                    text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                Path path = Path.of(location);
                if (!Files.exists(path)) {
                    if (required) {
                        throw new IllegalStateException("Settings file not found: " + location);
                    }
                    return Map.of();
                }
                text = Files.readString(path, StandardCharsets.UTF_8);
            }
            if (text.contains("<%")) {
                throw new IllegalStateException(
                    "Settings file " + location + " contains ERB; Spring has no ERB evaluation — "
                        + "pre-render it or move the values into the overrides file");
            }
            return parseTier(text, location);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read settings file " + location, exception);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseTier(String text, String source) {
        Object parsed = RailsYaml.read(text);
        if (parsed == null) {
            return Map.of();
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Settings file " + source + " did not parse to a mapping");
        }
        return normalizeMap((Map<Object, Object>) map);
    }

    /** ActiveSupport {@code deep_merge!}: Hash+Hash recurses; anything else (incl. nil/false, arrays) replaces. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            Object existing = merged.get(entry.getKey());
            Object incoming = entry.getValue();
            if (existing instanceof Map<?, ?> && incoming instanceof Map<?, ?>) {
                merged.put(entry.getKey(), deepMerge((Map<String, Object>) existing, (Map<String, Object>) incoming));
            } else {
                merged.put(entry.getKey(), incoming);
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    /** Strips a leading ':' from string keys at every level (indifferent access); values unchanged. */
    private static Map<String, Object> normalizeMap(Map<Object, Object> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith(":")) {
                key = key.substring(1);
            }
            normalized.put(key, normalizeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(normalized);
    }

    @SuppressWarnings("unchecked")
    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap((Map<Object, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object element : list) {
                normalized.add(normalizeValue(element));
            }
            return Collections.unmodifiableList(normalized);
        }
        return value;
    }

    private record Snapshot(Map<String, Object> values, Instant loadedAt,
        ConcurrentHashMap<String, Object> memo) {
    }
}
