package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.support.RailsBase64;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.service.read.UserPreferenceService.ListDefaults;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link UserPreferenceService} to Rails {@code Preference#[]}, which stores
 * {@code Base64.encode64(value.to_json)} and reads it back with {@code JSON.parse(Base64.decode64(...))},
 * and to {@code EntitiesController}, which uses {@code pref[:"<controller>_per_page"]} and
 * {@code pref[:"<controller>_sort_by"]} as pagination/sort defaults.
 */
class UserPreferenceServiceTest {

    private PreferenceRepository repository;
    private UserPreferenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(PreferenceRepository.class);
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(anyLong(), anyString())).thenReturn(Optional.empty());
        service = new UserPreferenceService(repository, new ObjectMapper());
    }

    @Test
    void usesControllerNameSuffixedPreferenceKeys() {
        service.listDefaults(7L, "opportunities");

        verify(repository).findFirstByUserIdAndNameOrderByIdAsc(7L, "opportunities_per_page");
        verify(repository).findFirstByUserIdAndNameOrderByIdAsc(7L, "opportunities_sort_by");
    }

    @Test
    void missingPreferencesYieldNullDefaults() {
        ListDefaults defaults = service.listDefaults(1L, "accounts");

        assertThat(defaults.perPage()).isNull();
        assertThat(defaults.sortBy()).isNull();
    }

    @Test
    void readsRailsEncodedJsonNumberAndString() {
        stub("accounts_per_page", "25");
        stub("accounts_sort_by", "\"accounts.name DESC\"");

        ListDefaults defaults = service.listDefaults(1L, "accounts");

        assertThat(defaults.perPage()).isEqualTo(25);
        assertThat(defaults.sortBy()).isEqualTo("accounts.name DESC");
    }

    @Test
    void numericStringPerPageIsAcceptedLikeRailsPaginate() {
        stub("accounts_per_page", "\"15\"");

        assertThat(service.listDefaults(1L, "accounts").perPage()).isEqualTo(15);
    }

    @Test
    void zeroNegativeFractionalAndNonNumericPerPageAreIgnored() {
        stub("accounts_per_page", "0");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        stub("accounts_per_page", "-5");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        stub("accounts_per_page", "2.5");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        stub("accounts_per_page", "\"abc\"");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        stub("accounts_per_page", "\"\"");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        stub("accounts_per_page", "null");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        stub("accounts_per_page", "99999999999999");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();
    }

    @Test
    void nonTextualSortByIsIgnored() {
        stub("accounts_sort_by", "12");
        assertThat(service.listDefaults(1L, "accounts").sortBy()).isNull();

        stub("accounts_sort_by", "[\"accounts.name ASC\"]");
        assertThat(service.listDefaults(1L, "accounts").sortBy()).isNull();

        stub("accounts_sort_by", "null");
        assertThat(service.listDefaults(1L, "accounts").sortBy()).isNull();
    }

    @Test
    void malformedJsonAndNullValuesAreIgnored() {
        stub("accounts_per_page", "not json at all");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        Preference nullValue = new Preference();
        nullValue.setName("accounts_sort_by");
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(1L, "accounts_sort_by"))
            .thenReturn(Optional.of(nullValue));
        assertThat(service.listDefaults(1L, "accounts").sortBy()).isNull();
    }

    @Test
    void rawNonBase64GarbageDoesNotPropagate() {
        Preference garbage = new Preference();
        garbage.setName("accounts_per_page");
        garbage.setValue("%%% not base64 %%%");
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(1L, "accounts_per_page")).thenReturn(Optional.of(garbage));

        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();
    }

    private void stub(String name, String json) {
        Preference preference = new Preference();
        preference.setName(name);
        preference.setValue(RailsBase64.encode64(json));
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(1L, name)).thenReturn(Optional.of(preference));
    }
}
