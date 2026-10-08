package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.support.RailsBase64;
import com.fatfreecrm.repository.PreferenceRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Rails {@code current_user.pref[:accounts_per_page]} / {@code [:accounts_sort_by]} are Base64 YAML/JSON blobs;
 * the Spring reader must ignore anything that is not a positive integer or a text sort key.
 */
class UserPreferenceServiceTest {

    private static final long USER_ID = 42L;

    private final PreferenceRepository preferenceRepository = mock(PreferenceRepository.class);
    private final UserPreferenceService service = new UserPreferenceService(preferenceRepository, new ObjectMapper());

    @Test
    void readsIntegerPerPageAndTextualSortBy() {
        stub("accounts_per_page", "5");
        stub("accounts_sort_by", "\"accounts.name DESC\"");

        UserPreferenceService.ListDefaults defaults = service.listDefaults(USER_ID, "accounts");

        assertThat(defaults.perPage()).isEqualTo(5);
        assertThat(defaults.sortBy()).isEqualTo("accounts.name DESC");
    }

    @Test
    void returnsNullsWhenNoPreferenceIsStored() {
        UserPreferenceService.ListDefaults defaults = service.listDefaults(USER_ID, "accounts");

        assertThat(defaults.perPage()).isNull();
        assertThat(defaults.sortBy()).isNull();
    }

    @Test
    void rejectsZeroAndNegativePerPage() {
        stub("accounts_per_page", "0");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stub("accounts_per_page", "-5");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stub("accounts_per_page", "\"-5\"");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();
    }

    @Test
    void rejectsNonIntegralAndOverflowingPerPage() {
        stub("accounts_per_page", "2.5");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stub("accounts_per_page", "99999999999");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        stub("accounts_per_page", "\"20 rows\"");
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();
    }

    @Test
    void rejectsNonTextualSortBy() {
        stub("accounts_sort_by", "7");
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isNull();

        stub("accounts_sort_by", "{\"column\":\"name\"}");
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isNull();

        stub("accounts_sort_by", "null");
        assertThat(service.listDefaults(USER_ID, "accounts").sortBy()).isNull();
    }

    @Test
    void ignoresUndecodableAndUnparseableValues() {
        Preference malformed = new Preference();
        malformed.setName("accounts_per_page");
        malformed.setValue("%%% not base64 %%%");
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(USER_ID, "accounts_per_page"))
            .thenReturn(Optional.of(malformed));
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        Preference notJson = new Preference();
        notJson.setName("accounts_per_page");
        notJson.setValue(RailsBase64.encode64("--- !ruby/object:Foo {}"));
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(USER_ID, "accounts_per_page"))
            .thenReturn(Optional.of(notJson));
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();

        Preference empty = new Preference();
        empty.setName("accounts_per_page");
        empty.setValue(null);
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(USER_ID, "accounts_per_page"))
            .thenReturn(Optional.of(empty));
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isNull();
    }

    @Test
    void preferencesAreScopedByControllerName() {
        stub("accounts_per_page", "5");

        assertThat(service.listDefaults(USER_ID, "campaigns").perPage()).isNull();
        assertThat(service.listDefaults(USER_ID, "accounts").perPage()).isEqualTo(5);
    }

    private void stub(String name, String json) {
        Preference preference = new Preference();
        preference.setName(name);
        preference.setJsonValue(json);
        when(preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(USER_ID, name))
            .thenReturn(Optional.of(preference));
    }
}
