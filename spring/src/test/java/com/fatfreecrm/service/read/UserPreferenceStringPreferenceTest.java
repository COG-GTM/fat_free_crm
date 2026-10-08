package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.repository.PreferenceRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link UserPreferenceService#stringPreference} backs the activity feed defaults
 * ({@code current_user.pref[:activity_asset]} etc. in Rails {@code HomeController}). Rails reads the
 * Base64-wrapped JSON value and uses it only when it is a string, so every non-string or undecodable
 * value must degrade to "no preference" instead of failing the feed request.
 */
class UserPreferenceStringPreferenceTest {

    private static final long USER_ID = 11L;

    private PreferenceRepository preferenceRepository;
    private UserPreferenceService service;

    @BeforeEach
    void setUp() {
        preferenceRepository = mock(PreferenceRepository.class);
        service = new UserPreferenceService(preferenceRepository, new ObjectMapper());
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(anyLong(), anyString()))
            .thenReturn(Optional.empty());
    }

    @Test
    void returnsDecodedJsonStringForTheRequestedUserAndName() {
        stub("activity_asset", encoded("\"accounts\""));

        assertThat(service.stringPreference(USER_ID, "activity_asset")).contains("accounts");
        verify(preferenceRepository).findFirstByUserIdAndNameOrderByIdAsc(USER_ID, "activity_asset");
    }

    @Test
    void acceptsRailsMimeWrappedBase64WithLineBreaks() {
        String mime = Base64.getMimeEncoder(4, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString("\"one_week\"".getBytes(StandardCharsets.UTF_8));
        stub("activity_duration", mime);

        assertThat(service.stringPreference(USER_ID, "activity_duration")).contains("one_week");
    }

    @Test
    void preservesEmptyAndWhitespaceStrings() {
        stub("activity_user", encoded("\"\""));
        stub("activity_event", encoded("\"  \""));

        assertThat(service.stringPreference(USER_ID, "activity_user")).contains("");
        assertThat(service.stringPreference(USER_ID, "activity_event")).contains("  ");
    }

    @Test
    void isEmptyWhenNoPreferenceRowExists() {
        assertThat(service.stringPreference(USER_ID, "activity_asset")).isEmpty();
    }

    @Test
    void ignoresNonStringJsonValues() {
        stub("activity_asset", encoded("25"));
        stub("activity_event", encoded("true"));
        stub("activity_user", encoded("[\"alice\"]"));
        stub("activity_duration", encoded("{\"value\":\"one_day\"}"));
        stub("activity_null", encoded("null"));

        assertThat(service.stringPreference(USER_ID, "activity_asset")).isEmpty();
        assertThat(service.stringPreference(USER_ID, "activity_event")).isEmpty();
        assertThat(service.stringPreference(USER_ID, "activity_user")).isEmpty();
        assertThat(service.stringPreference(USER_ID, "activity_duration")).isEmpty();
        assertThat(service.stringPreference(USER_ID, "activity_null")).isEmpty();
    }

    @Test
    void ignoresNullMalformedBase64AndMalformedJsonValues() {
        stub("activity_asset", null);
        stub("activity_event", "%%% not base64 %%%");
        stub("activity_user", encoded("{not json"));
        stub("activity_duration", encoded(""));

        assertThat(service.stringPreference(USER_ID, "activity_asset")).isEmpty();
        assertThat(service.stringPreference(USER_ID, "activity_event")).isEmpty();
        assertThat(service.stringPreference(USER_ID, "activity_user")).isEmpty();
        assertThat(service.stringPreference(USER_ID, "activity_duration")).isEmpty();
    }

    @Test
    void doesNotFallBackToAnotherUsersPreference() {
        stub("activity_asset", encoded("\"accounts\""));

        assertThat(service.stringPreference(USER_ID + 1, "activity_asset")).isEmpty();
    }

    private void stub(String name, String rawValue) {
        Preference preference = new Preference();
        preference.setName(name);
        preference.setValue(rawValue);
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(eq(USER_ID), eq(name)))
            .thenReturn(Optional.of(preference));
    }

    private static String encoded(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
