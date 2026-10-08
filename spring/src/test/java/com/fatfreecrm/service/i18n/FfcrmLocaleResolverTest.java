package com.fatfreecrm.service.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.config.I18nProperties;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import com.fatfreecrm.service.read.UserPreferenceService;
import com.fatfreecrm.service.settings.SettingsService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class FfcrmLocaleResolverTest {

    private final UserPreferenceService userPreferenceService = mock(UserPreferenceService.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private I18nService i18nService;
    private FfcrmLocaleResolver resolver;

    @BeforeEach
    void setUp() {
        Properties en = new Properties();
        Properties de = new Properties();
        Properties ptBr = new Properties();
        i18nService = new I18nService(
            new I18nProperties(List.of("en"), Map.of("en", List.of("en-US"))),
            Map.of("en-US", en, "de", de, "pt-BR", ptBr));
        resolver = new FfcrmLocaleResolver(userPreferenceService, settingsService, i18nService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(long userId) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").claim("sub", userId).build();
        SecurityContextHolder.getContext().setAuthentication(
            new FfcrmAuthenticationToken(jwt, List.of(), new AuthenticatedUser(userId, "alice", false)));
    }

    @Test
    void userPreferenceLocaleWins() {
        authenticate(42);
        when(userPreferenceService.stringPreference(42, "locale")).thenReturn(Optional.of("de"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "pt-BR");
        when(settingsService.string("locale")).thenReturn(Optional.of("en-US"));
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.forLanguageTag("de"));
    }

    @Test
    void acceptLanguageMatchesBundleByTagThenLanguage() {
        when(settingsService.string("locale")).thenReturn(Optional.of("en-US"));
        MockHttpServletRequest exact = new MockHttpServletRequest();
        exact.addHeader("Accept-Language", "pt-BR,de;q=0.9");
        assertThat(resolver.resolveLocale(exact)).isEqualTo(Locale.forLanguageTag("pt-BR"));

        MockHttpServletRequest sameLanguage = new MockHttpServletRequest();
        sameLanguage.addHeader("Accept-Language", "de-CH");
        assertThat(resolver.resolveLocale(sameLanguage)).isEqualTo(Locale.forLanguageTag("de"));
    }

    @Test
    void settingsLocaleThenDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(settingsService.string("locale")).thenReturn(Optional.of("pt-BR"));
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.forLanguageTag("pt-BR"));

        when(settingsService.string("locale")).thenReturn(Optional.empty());
        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.forLanguageTag("en-US"));
    }
}
