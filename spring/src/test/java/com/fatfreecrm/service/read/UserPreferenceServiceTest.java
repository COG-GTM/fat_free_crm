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
 * Edge cases for Rails {@code current_user.pref[:<controller>_per_page]} / {@code _sort_by} decoding:
 * Rails stores preferences as Base64-wrapped JSON, so malformed rows must degrade to "no preference"
 * (so the controller falls back to {@code Setting} defaults) rather than fail the list request.
 */
class UserPreferenceServiceTest {

    private PreferenceRepository preferenceRepository;
    private UserPreferenceService service;

    @BeforeEach
    void setUp() {
        preferenceRepository = mock(PreferenceRepository.class);
        service = new UserPreferenceService(preferenceRepository, new ObjectMapper());
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(anyLong(), anyString()))
            .thenReturn(Optional.empty());
    }

    private void stub(String name, String rawValue) {
        Preference preference = new Preference();
        preference.setName(name);
        preference.setValue(rawValue);
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(eq(7L), eq(name)))
            .thenReturn(Optional.of(preference));
    }

    private static String encoded(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void looksUpControllerScopedPreferenceNames() {
        stub("accounts_per_page", encoded("25"));
        stub("accounts_sort_by", encoded("\"accounts.name ASC\""));

        UserPreferenceService.ListDefaults defaults = service.listDefaults(7L, "accounts");

        assertThat(defaults.perPage()).isEqualTo(25);
        assertThat(defaults.sortBy()).isEqualTo("accounts.name ASC");
        verify(preferenceRepository).findFirstByUserIdAndNameOrderByIdAsc(7L, "accounts_per_page");
        verify(preferenceRepository).findFirstByUserIdAndNameOrderByIdAsc(7L, "accounts_sort_by");
    }

    @Test
    void absentPreferencesYieldNulls() {
        UserPreferenceService.ListDefaults defaults = service.listDefaults(7L, "campaigns");
        assertThat(defaults.perPage()).isNull();
        assertThat(defaults.sortBy()).isNull();
    }

    @Test
    void numericStringPerPageIsAccepted() {
        stub("leads_per_page", encoded("\"40\""));
        assertThat(service.listDefaults(7L, "leads").perPage()).isEqualTo(40);
    }

    @Test
    void zeroNegativeAndOverflowingPerPageAreIgnored() {
        stub("accounts_per_page", encoded("0"));
        assertThat(service.listDefaults(7L, "accounts").perPage()).isNull();

        stub("accounts_per_page", encoded("-5"));
        assertThat(service.listDefaults(7L, "accounts").perPage()).isNull();

        stub("accounts_per_page", encoded("99999999999"));
        assertThat(service.listDefaults(7L, "accounts").perPage()).isNull();

        stub("accounts_per_page", encoded("12.5"));
        assertThat(service.listDefaults(7L, "accounts").perPage()).isNull();
    }

    @Test
    void nonTextualSortByIsIgnored() {
        stub("accounts_sort_by", encoded("42"));
        assertThat(service.listDefaults(7L, "accounts").sortBy()).isNull();

        stub("accounts_sort_by", encoded("[\"name\"]"));
        assertThat(service.listDefaults(7L, "accounts").sortBy()).isNull();

        stub("accounts_sort_by", encoded("null"));
        assertThat(service.listDefaults(7L, "accounts").sortBy()).isNull();
    }

    @Test
    void nullMalformedBase64AndMalformedJsonValuesAreTreatedAsAbsent() {
        stub("accounts_per_page", null);
        stub("accounts_sort_by", null);
        UserPreferenceService.ListDefaults defaults = service.listDefaults(7L, "accounts");
        assertThat(defaults.perPage()).isNull();
        assertThat(defaults.sortBy()).isNull();

        stub("accounts_per_page", "%%%not-base64%%%");
        stub("accounts_sort_by", encoded("{not json"));
        defaults = service.listDefaults(7L, "accounts");
        assertThat(defaults.perPage()).isNull();
        assertThat(defaults.sortBy()).isNull();

        stub("accounts_per_page", encoded(""));
        assertThat(service.listDefaults(7L, "accounts").perPage()).isNull();
    }

    @Test
    void mimeWrappedBase64FromRailsIsDecoded() {
        String railsStyle = Base64.getMimeEncoder().encodeToString(
            "\"accounts.created_at DESC\"".getBytes(StandardCharsets.UTF_8)) + "\n";
        stub("accounts_sort_by", railsStyle);
        assertThat(service.listDefaults(7L, "accounts").sortBy()).isEqualTo("accounts.created_at DESC");
    }
}
