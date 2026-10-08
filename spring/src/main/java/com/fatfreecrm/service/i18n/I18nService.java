package com.fatfreecrm.service.i18n;

import com.fatfreecrm.config.I18nProperties;
import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.util.ULocale;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * AB-273 (settings-i18n): loads the generated {@code classpath*:i18n/messages_*.properties} bundles
 * (produced by {@code rake ffcrm:migration:i18n_properties} from {@code config/locales}) and formats
 * them with ICU4J {@link MessageFormat} so plural selection and interpolation match Rails' i18n gem.
 *
 * <p>Format cache is keyed by (locale, key) and the {@link ULocale} used is the locale where the key
 * was FOUND — Rails' {@code I18n::Backend::Fallbacks#translate} re-dispatches with the fallback
 * locale, so its pluralizer applies. Arguments: {@code count} is passed to ICU as a Number (the
 * generated patterns format it via {@code ::group-off}, matching Rails {@code count.to_s}); every
 * other argument is converted to String Ruby-{@code to_s}-style (null to "") so ICU never applies
 * locale number/date formatting. A missing interpolation argument throws
 * {@link MissingInterpolationArgumentException}; a key missing in the whole chain yields
 * {@code "Translation missing: <tag>.<key>"}.
 */
@Service
public class I18nService {

    private static final Pattern BUNDLE = Pattern.compile("messages_([a-zA-Z_]+)\\.properties$");
    /** ICU formats generated for plural hashes always contain `{name, type, ...}`; bare `%{name}`
     * Rails interpolations (emitted verbatim) and literal braces never match this. */
    private static final Pattern ICU_MARKER = Pattern.compile("\\{[A-Za-z0-9_]+\\s*,");
    /** Rails-style interpolation placeholders handled by {@link #interpolate}: `%{name}` and
     * `%<name>s`; a literal `%%` is unescaped to `%`. */
    private static final Pattern RUBY_INTERPOLATION = Pattern.compile("%%|%\\{([^}]+)\\}|%<([A-Za-z_]\\w*)>s?");

    private final Map<String, Properties> bundles;
    private final I18nProperties properties;
    private final ConcurrentHashMap<String, MessageFormat> formats = new ConcurrentHashMap<>();

    @Autowired
    public I18nService(I18nProperties properties, ResourcePatternResolver resourceResolver) throws IOException {
        this(properties, loadBundles(resourceResolver));
    }

    I18nService(I18nProperties properties, Map<String, Properties> bundles) {
        this.properties = properties;
        this.bundles = bundles;
    }

    private static Map<String, Properties> loadBundles(ResourcePatternResolver resolver) throws IOException {
        Map<String, Properties> bundles = new LinkedHashMap<>();
        for (Resource resource : resolver.getResources("classpath*:i18n/messages_*.properties")) {
            Matcher matcher = BUNDLE.matcher(resource.getFilename() == null ? "" : resource.getFilename());
            if (!matcher.find()) {
                continue;
            }
            Properties props = new Properties();
            try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            bundles.put(matcher.group(1).replace('_', '-'), props);
        }
        return java.util.Collections.unmodifiableMap(bundles);
    }

    public Set<String> availableLocales() {
        return bundles.keySet();
    }

    /** Resolved pattern for {@code key} in {@code tag}, or empty when missing in the whole chain. */
    public Optional<String> find(String key, Locale locale) {
        return findEntry(key, locale.toLanguageTag()).map(Map.Entry::getValue);
    }

    public String t(String key, Locale locale) {
        return t(key, locale, Map.of());
    }

    public String t(String key, Locale locale, Map<String, Object> args) {
        String tag = locale.toLanguageTag();
        Optional<Map.Entry<ULocale, String>> entry = findEntry(key, tag);
        if (entry.isEmpty()) {
            return "Translation missing: " + tag + "." + key;
        }
        String pattern = entry.get().getValue();
        if (!ICU_MARKER.matcher(pattern).find()) {
            // Rails semantics: %{name} strings are interpolated only when args are passed;
            // with no args the raw pattern is returned unchanged.
            return args.isEmpty() ? pattern : interpolate(key, pattern, args);
        }
        MessageFormat format = formats.computeIfAbsent(
            entry.get().getKey() + " " + key,
            ignored -> new MessageFormat(pattern, entry.get().getKey()));
        return format.format(prepareArgs(key, pattern, args));
    }

    private String interpolate(String key, String pattern, Map<String, Object> args) {
        Matcher matcher = RUBY_INTERPOLATION.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            if (matcher.group().equals("%%")) {
                matcher.appendReplacement(out, "%");
                continue;
            }
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (!args.containsKey(name)) {
                throw new MissingInterpolationArgumentException(pattern, name);
            }
            Object value = args.get(name);
            matcher.appendReplacement(out,
                Matcher.quoteReplacement(value == null ? "" : rubyToString(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Fallback chain for a BCP-47 tag, per the fixture-captured Rails {@code I18n::Locale::Fallbacks}. */
    public List<String> fallbackChain(String tag) {
        List<String> override = properties.fallbackOverrides().get(tag);
        if (override != null) {
            return override;
        }
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        String current = tag;
        while (current != null && !current.isEmpty()) {
            chain.add(current);
            int dash = current.lastIndexOf('-');
            current = dash < 0 ? null : current.substring(0, dash);
        }
        chain.addAll(properties.fallbackDefaults());
        return List.copyOf(chain);
    }

    private Optional<Map.Entry<ULocale, String>> findEntry(String key, String tag) {
        for (String candidate : fallbackChain(tag)) {
            Properties bundle = bundles.get(candidate);
            if (bundle != null) {
                String pattern = bundle.getProperty(key);
                if (pattern != null) {
                    return Optional.of(Map.entry(ULocale.forLanguageTag(candidate), pattern));
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> prepareArgs(String key, String pattern, Map<String, Object> args) {
        Set<String> needed = new LinkedHashSet<>();
        collectArguments(pattern, 0, pattern.length(), needed);
        Map<String, Object> prepared = new LinkedHashMap<>();
        for (String name : needed) {
            if (!args.containsKey(name)) {
                throw new MissingInterpolationArgumentException(pattern, name);
            }
            Object value = args.get(name);
            if ("count".equals(name) && value instanceof Number number) {
                prepared.put(name, number);
            } else {
                prepared.put(name, value == null ? "" : rubyToString(value));
            }
        }
        return prepared;
    }

    private static String rubyToString(Object value) {
        return String.valueOf(value);
    }

    /**
     * Collects ICU argument names ({@code {name}} or {@code {name, type, ...}}) between
     * {@code start} and {@code end}, honouring ApostropheMode.DOUBLE_OPTIONAL quoting and
     * recursing into {@code plural}/{@code select}/{@code selectordinal} branch bodies — the
     * {@code label{body}} structure is skipped so branch text like {@code one{day}} is not
     * mistaken for an argument.
     */
    private static void collectArguments(String pattern, int start, int end, Set<String> out) {
        int i = start;
        while (i < end) {
            char ch = pattern.charAt(i);
            if (ch == '\'') {
                // Quoted literal: '' escapes; a single ' opens a quote that runs to the next '.
                i++;
                if (i < end && pattern.charAt(i) == '\'') {
                    i++;
                    continue;
                }
                while (i < end && pattern.charAt(i) != '\'') {
                    i++;
                }
                i++;
                continue;
            }
            if (ch == '{') {
                i = parseArgument(pattern, i, end, out);
                continue;
            }
            i++;
        }
    }

    private static int parseArgument(String pattern, int open, int end, Set<String> out) {
        int i = open + 1;
        StringBuilder name = new StringBuilder();
        while (i < end && (Character.isLetterOrDigit(pattern.charAt(i)) || pattern.charAt(i) == '_')) {
            name.append(pattern.charAt(i));
            i++;
        }
        if (name.length() == 0) {
            // Not a plain argument token (e.g. a plural =0 label brace).
            return open + 1;
        }
        if (i >= end || pattern.charAt(i) == '}') {
            out.add(name.toString());
            return i < end ? i + 1 : i;
        }
        if (pattern.charAt(i) != ',') {
            return i;
        }
        // Read the format type word.
        i++;
        int typeStart = i;
        while (i < end && pattern.charAt(i) != ',' && pattern.charAt(i) != '}') {
            i++;
        }
        String type = pattern.substring(typeStart, i).trim();
        if (type.equals("plural") || type.equals("select") || type.equals("selectordinal")) {
            // The selector variable is itself an argument; scan each {body} recursively.
            out.add(name.toString());
            i++;
            while (i < end) {
                char ch = pattern.charAt(i);
                if (ch == '}') {
                    return i + 1;
                }
                if (ch == '{') {
                    int close = matchingBrace(pattern, i, end);
                    collectArguments(pattern, i + 1, close, out);
                    i = close + 1;
                    continue;
                }
                i++;
            }
            return i;
        }
        // Non-choice format (number/date/time/custom) — argument by name.
        out.add(name.toString());
        return i + 1;
    }

    private static int matchingBrace(String pattern, int open, int end) {
        int depth = 0;
        for (int i = open; i < end; i++) {
            char ch = pattern.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return end;
    }
}
