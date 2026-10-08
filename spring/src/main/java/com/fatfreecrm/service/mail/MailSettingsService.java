package com.fatfreecrm.service.mail;

import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.SettingRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MailSettingsService {

    private final SettingRepository settingRepository;
    private final String host;
    private final String locale;
    private final String defaultAccess;
    private final String smtpFrom;
    private final String commentReplyAddress;
    private final String dropboxAddress;

    public MailSettingsService(
        SettingRepository settingRepository,
        @Value("${ffcrm.mail.settings.host:}") String host,
        @Value("${ffcrm.mail.settings.locale:en-US}") String locale,
        @Value("${ffcrm.mail.settings.default-access:Public}") String defaultAccess,
        @Value("${ffcrm.mail.settings.smtp.from:Fat Free CRM <noreply@fatfreecrm.com>}") String smtpFrom,
        @Value("${ffcrm.mail.settings.email-comment-replies.address:noreply@fatfreecrm.com}")
        String commentReplyAddress,
        @Value("${ffcrm.mail.settings.email-dropbox.address:}") String dropboxAddress
    ) {
        this.settingRepository = settingRepository;
        this.host = host;
        this.locale = locale;
        this.defaultAccess = defaultAccess;
        this.smtpFrom = smtpFrom;
        this.commentReplyAddress = commentReplyAddress;
        this.dropboxAddress = dropboxAddress;
    }

    public Map<String, Object> section(String name) {
        Map<String, Object> defaults = switch (name) {
            case "smtp" -> Map.of("from", smtpFrom);
            case "email_comment_replies" -> Map.of("address", commentReplyAddress);
            case "email_dropbox" -> Map.of("address", dropboxAddress);
            default -> Map.of();
        };
        Optional<Setting> setting = settingRepository.findByName(name);
        if (setting.isEmpty()) {
            return defaults;
        }
        Map<String, Object> result = new LinkedHashMap<>(defaults);
        Object parsed = setting.get().getParsedValue();
        if (parsed instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(normalizeKey(key), value));
        }
        return result;
    }

    public Object value(String section, String key) {
        return section(section).get(normalizeKey(key));
    }

    public String smtpFrom() {
        return stringValue(value("smtp", "from"), smtpFrom);
    }

    public String commentReplyFrom(String userName) {
        String configured = stringValue(value("email_comment_replies", "address"), "");
        if (configured.isBlank()) {
            configured = smtpFrom();
        }
        if (userName != null && !userName.isBlank() && !configured.matches(".*<.+>\\s*$")) {
            return userName + " <" + configured + ">";
        }
        return configured;
    }

    public String locale() {
        return scalarSetting("locale", locale);
    }

    public String defaultAccess() {
        return defaultAccess;
    }

    public String host() {
        return scalarSetting("host", host);
    }

    private String scalarSetting(String name, String fallback) {
        return settingRepository.findByName(name)
            .map(Setting::getParsedValue)
            .map(Object::toString)
            .filter(value -> !value.isBlank())
            .orElse(fallback);
    }

    private static String normalizeKey(Object key) {
        return String.valueOf(key).replaceFirst("^:", "");
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
