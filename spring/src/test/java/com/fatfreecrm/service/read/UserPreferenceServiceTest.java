package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.repository.PreferenceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rails stores {@code current_user.pref[:accounts_per_page]} / {@code [:accounts_sort_by]} as
 * Base64(JSON) rows in {@code preferences}; {@code set_options} falls back to the model default when
 * the preference is absent. These tests pin the decoding rules for every value shape a Rails row can hold.
 */
class UserPreferenceServiceTest {

    private static final long USER_ID = 42L;

    private PreferenceRepository preferenceRepository;
    private UserPreferenceService service;

    @BeforeEach
    void setUp() {
        preferenceRepository = mock(PreferenceRepository.class);
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(anyLong(), anyString()))
            .thenReturn(Optional.empty());
        service = new UserPreferenceService(preferenceRepository, new ObjectMapper());
    }

    @Test
    void absentPreferencesYieldNullDefaults() {
        UserPreferenceService.ListDefaults defaults = service.listDefaults(USER_ID, "accounts");

        assertThat(defaults.perPage()).isNull();
        assertThat(defaults.sortBy()).isNull();
    }

    @Test
    void looksUpPreferencesByControllerNamePrefix() {
        stubJson("contacts_per_page", "15");
        stubJson("contacts_sort_by", "\"contacts.last_name ASC\"");

        assertThat(service.listDefaults(USER_ID, "contacts"))
            .isEqualTo(new UserPreferenceService.ListDefaults(15, "contacts.last_name ASC"));
        assertThat(service.listDefaults(USER_ID, "accounts"))
            .isEqualTo(new UserPreferenceService.ListDefaults(null, null));
    }

    @Test
    void decodesRailsIntegerAndNumericStringPerPage() {
        stubJson("accounts_per_page", "25");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isEqualTo(25);

        stubJson("accounts_per_page", "\"10\"");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isEqualTo(10);
    }

    @Test
    void rejectsNonPositivePerPage() {
        stubJson("accounts_per_page", "0");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stubJson("accounts_per_page", "-5");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stubJson("accounts_per_page", "\"-1\"");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();
    }

    @Test
    void rejectsNonIntegralOrOverflowingPerPage() {
        stubJson("accounts_per_page", "2.5");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stubJson("accounts_per_page", "99999999999");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stubJson("accounts_per_page", "\"99999999999\"");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stubJson("accounts_per_page", "true");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stubJson("accounts_per_page", "[20]");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stubJson("accounts_per_page", "null");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();
    }

    @Test
    void sortByMustBeAJsonString() {
        stubJson("accounts_sort_by", "\"accounts.name ASC\"");
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isEqualTo("accounts.name ASC");

        stubJson("accounts_sort_by", "5");
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isNull();

        stubJson("accounts_sort_by", "{\"name\":\"asc\"}");
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isNull();

        stubJson("accounts_sort_by", "null");
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isNull();
    }

    @Test
    void ignoresNullAndUndecodableStoredValues() {
        Preference nullValue = new Preference();
        nullValue.setName("accounts_per_page");
        nullValue.setValue(null);
        stub("accounts_per_page", nullValue);
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        Preference notJson = new Preference();
        notJson.setName("accounts_per_page");
        notJson.setJsonValue("not json at all");
        stub("accounts_per_page", notJson);
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        Preference notBase64 = new Preference();
        notBase64.setName("accounts_sort_by");
        notBase64.setValue("%%% not base64 %%%");
        stub("accounts_sort_by", notBase64);
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isNull();
    }

    @Test
    void acceptsRailsLineWrappedBase64() {
        Preference wrapped = new Preference();
        wrapped.setName("accounts_sort_by");
        wrapped.setValue("ImFjY291bnRzLmNyZWF0ZWRfYXQgREVTQyI=\n");
        stub("accounts_sort_by", wrapped);

        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isEqualTo("accounts.created_at DESC");
    }

    private void stubJson(String name, String json) {
        Preference preference = new Preference();
        preference.setName(name);
        preference.setJsonValue(json);
        stub(name, preference);
    }

    private void stub(String name, Preference preference) {
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(USER_ID, name))
            .thenReturn(Optional.of(preference));
    }
}
