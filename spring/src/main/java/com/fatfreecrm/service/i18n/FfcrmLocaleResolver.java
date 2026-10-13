package com.fatfreecrm.service.i18n;

import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.read.UserPreferenceService;
import com.fatfreecrm.service.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * AB-273 (settings-i18n): request locale resolution mirroring Rails
 * application_controller.rb:114-121 ({@code set_context}):
 * <ol>
 *   <li>an authenticated user's non-blank {@code locale} preference
 *       ({@code current_user.preference[:locale]}, via {@link UserPreferenceService}),</li>
 *   <li>{@code Accept-Language} matched against the generated bundles (exact tag, then same
 *       language),</li>
 *   <li>the {@code locale} setting (Rails {@code Setting.locale}),</li>
 *   <li>{@code en-US}.</li>
 * </ol>
 * {@code setLocale} is unsupported, like the superclass.
 */
@Component("localeResolver")
public class FfcrmLocaleResolver extends AcceptHeaderLocaleResolver {

    private static final Locale FALLBACK = Locale.forLanguageTag("en-US");

    private final UserPreferenceService userPreferenceService;
    private final SettingsService settingsService;
    private final I18nService i18nService;

    public FfcrmLocaleResolver(
        UserPreferenceService userPreferenceService,
        SettingsService settingsService,
        I18nService i18nService) {
        this.userPreferenceService = userPreferenceService;
        this.settingsService = settingsService;
        this.i18nService = i18nService;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Locale userLocale = authenticatedUserLocale();
        if (userLocale != null) {
            return userLocale;
        }
        Locale header = acceptLanguageLocale(request);
        if (header != null) {
            return header;
        }
        return settingsService.string("locale")
            .filter(tag -> !tag.isBlank())
            .map(Locale::forLanguageTag)
            .orElse(FALLBACK);
    }

    private Locale authenticatedUserLocale() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof FfcrmAuthenticationToken token) {
            AuthenticatedUser user = token.getAuthenticatedUser();
            if (user != null) {
                return userPreferenceService.stringPreference(user.id(), "locale")
                    .filter(tag -> !tag.isBlank())
                    .map(Locale::forLanguageTag)
                    .orElse(null);
            }
        }
        return null;
    }

    private Locale acceptLanguageLocale(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return null;
        }
        List<Locale> accepted;
        try {
            accepted = Locale.filter(
                Locale.LanguageRange.parse(header),
                i18nService.availableLocales().stream().map(Locale::forLanguageTag).toList());
        } catch (IllegalArgumentException exception) {
            return null;
        }
        // Locale.filter already orders by preference and matches exact tags before language
        // subranges; additionally fall back to same-language bundles in range order.
        if (!accepted.isEmpty()) {
            return accepted.get(0);
        }
        for (Locale.LanguageRange range : Locale.LanguageRange.parse(header)) {
            String language = range.getRange().split("-")[0];
            for (String tag : i18nService.availableLocales()) {
                Locale locale = Locale.forLanguageTag(tag);
                if (locale.getLanguage().equals(language)) {
                    return locale;
                }
            }
        }
        return null;
    }
}
