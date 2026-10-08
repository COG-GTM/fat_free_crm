package com.fatfreecrm.service.write.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.service.write.RailsParameterMissing;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code Admin::SettingsController#update}: {@code settings_params} permit (filter order is
 * write order), {@code '1'} → boolean coercion (any other value, including JSON {@code true}, is
 * {@code false}), {@code user_signup} → Symbol, then {@code Setting[key] = value} per key with the
 * value Psych-serialized into {@code settings.value}. Not versioned.
 */
@Service
public class AdminSettingsWriteService {

    private static final List<String> SCALARS = List.of(
        "host", "base_url", "locale", "per_user_locale", "default_access", "user_signup",
        "compound_address", "task_calendar_with_time", "require_first_names", "require_last_names",
        "require_unique_account_names", "comments_visible_on_dashboard",
        "enforce_international_phone_format", "opportunity_default_stage");
    private static final List<String> ARRAYS = List.of(
        "background_info", "priority_countries", "account_category", "campaign_status",
        "lead_status", "lead_source", "opportunity_stage", "task_category", "task_bucket",
        "task_completed");
    private static final Map<String, List<String>> HASHES = orderedHashes();
    private static final Set<String> BOOLEANS = Set.of(
        "per_user_locale", "compound_address", "task_calendar_with_time", "require_first_names",
        "require_last_names", "require_unique_account_names", "comments_visible_on_dashboard",
        "enforce_international_phone_format");

    private final SettingRepository settingRepository;

    public AdminSettingsWriteService(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    @Transactional
    public void update(Map<String, JsonNode> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new RailsParameterMissing("settings");
        }
        Map<String, Object> settings = permit(raw);
        for (String key : BOOLEANS) {
            if (settings.containsKey(key)) {
                settings.put(key, "1".equals(settings.get(key)));
            }
        }
        for (String hash : List.of("email_dropbox", "email_comment_replies")) {
            if (settings.get(hash) instanceof Map<?, ?> map && map.containsKey("ssl")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mutable = (Map<String, Object>) map;
                mutable.put("ssl", "1".equals(mutable.get("ssl")));
            }
        }
        if (settings.get("user_signup") instanceof String signup) {
            settings.put("user_signup", new RubyYaml.Symbol(signup));
        }
        settings.forEach(this::write);
        // AB-273 integration point: evict SettingsService's TTL cache for these keys after commit.
    }

    /** {@code Setting[name] = value}: find_by_name || new, YAML-serialized, saved when changed. */
    private void write(String name, Object value) {
        String yaml = value == null ? null : RubyYaml.dump(value);
        Setting setting = settingRepository.findByName(name).orElseGet(() -> {
            Setting created = new Setting();
            created.setName(name);
            return created;
        });
        if (setting.getId() != null && Objects.equals(setting.getValue(), yaml)) {
            return;
        }
        setting.setValue(yaml);
        settingRepository.saveAndFlush(setting);
    }

    private static Map<String, Object> permit(Map<String, JsonNode> raw) {
        Map<String, Object> permitted = new LinkedHashMap<>();
        for (String key : SCALARS) {
            JsonNode node = raw.get(key);
            if (raw.containsKey(key) && isScalar(node)) {
                permitted.put(key, RubyYaml.fromJson(node));
            }
        }
        for (String key : ARRAYS) {
            JsonNode node = raw.get(key);
            if (isScalarArray(node)) {
                permitted.put(key, RubyYaml.fromJson(node));
            }
        }
        HASHES.forEach((key, allowed) -> {
            JsonNode node = raw.get(key);
            if (node == null || !node.isObject()) {
                return;
            }
            Map<String, Object> nested = new LinkedHashMap<>();
            for (String child : allowed) {
                if (node.has(child) && isScalar(node.get(child))) {
                    nested.put(child, RubyYaml.fromJson(node.get(child)));
                }
            }
            if ("email_dropbox".equals(key) && isScalarArray(node.get("address_aliases"))) {
                nested.put("address_aliases", RubyYaml.fromJson(node.get("address_aliases")));
            }
            permitted.put(key, nested);
        });
        return permitted;
    }

    private static boolean isScalar(JsonNode node) {
        return node == null || node.isNull() || node.isValueNode();
    }

    private static boolean isScalarArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isValueNode() && !item.isNull()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, List<String>> orderedHashes() {
        Map<String, List<String>> hashes = new LinkedHashMap<>();
        hashes.put("smtp", List.of("address", "from", "enable_starttls_auto", "port",
            "authentication", "user_name", "password"));
        hashes.put("email_dropbox", List.of("server", "port", "ssl", "address", "user", "password",
            "scan_folder", "attach_to_account", "move_to_folder", "move_invalid_to_folder"));
        hashes.put("email_comment_replies", List.of("server", "port", "ssl", "address", "user",
            "password", "scan_folder", "move_to_folder", "move_invalid_to_folder"));
        hashes.put("ai_prompts", List.of("about_my_business", "how_i_plan_to_use_ffcrm"));
        return hashes;
    }
}
