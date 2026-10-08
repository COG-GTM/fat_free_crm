package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.repository.PreferenceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rails stores preferences as {@code Base64.encode64(value.to_json)} and {@code ApplicationController}
 * falls back to its defaults whenever the stored value is unusable; these cases pin that tolerance.
 */
class UserPreferenceServiceTest {

    private PreferenceRepository repository;
    private UserPreferenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(PreferenceRepository.class);
        service = new UserPreferenceService(repository, new ObjectMapper());
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(anyLong(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void returnsNullsWhenNoPreferenceExists() {
        assertThat(service.listDefaults(7L, "accounts"))
            .isEqualTo(new UserPreferenceService.ListDefaults(null, null));
        assertThat(service.stringPreference(7L, "activity_asset")).isEmpty();
    }

    @Test
    void decodesRailsBase64JsonForPerPageAndSortBy() {
        stub(7L, "accounts_per_page", "25");
        stub(7L, "accounts_sort_by", "\"accounts.name ASC\"");

        assertThat(service.listDefaults(7L, "accounts"))
            .isEqualTo(new UserPreferenceService.ListDefaults(25, "accounts.name ASC"));
    }

    @Test
    void acceptsNumericStringsButRejectsNonPositiveAndNonIntegralPageSizes() {
        stub(1L, "leads_per_page", "\"40\"");
        assertThat(service.listDefaults(1L, "leads").perPage()).isEqualTo(40);

        stub(2L, "leads_per_page", "0");
        assertThat(service.listDefaults(2L, "leads").perPage()).isNull();

        stub(3L, "leads_per_page", "-5");
        assertThat(service.listDefaults(3L, "leads").perPage()).isNull();

        stub(4L, "leads_per_page", "\"-5\"");
        assertThat(service.listDefaults(4L, "leads").perPage()).isNull();

        stub(5L, "leads_per_page", "2.5");
        assertThat(service.listDefaults(5L, "leads").perPage()).isNull();

        stub(6L, "leads_per_page", "\"lots\"");
        assertThat(service.listDefaults(6L, "leads").perPage()).isNull();

        stub(8L, "leads_per_page", "99999999999");
        assertThat(service.listDefaults(8L, "leads").perPage()).isNull();
    }

    @Test
    void ignoresNonTextualSortValues() {
        stub(1L, "contacts_sort_by", "42");
        assertThat(service.listDefaults(1L, "contacts").sortBy()).isNull();

        stub(2L, "contacts_sort_by", "{\"column\":\"name\"}");
        assertThat(service.listDefaults(2L, "contacts").sortBy()).isNull();

        stub(3L, "contacts_sort_by", "null");
        assertThat(service.listDefaults(3L, "contacts").sortBy()).isNull();

        stub(4L, "activity_asset", "[\"Account\"]");
        assertThat(service.stringPreference(4L, "activity_asset")).isEmpty();
    }

    @Test
    void treatsUnparseableOrMissingPayloadsAsAbsent() {
        stub(1L, "accounts_per_page", "{not json");
        assertThat(service.listDefaults(1L, "accounts").perPage()).isNull();

        Preference nullValue = new Preference();
        nullValue.setName("accounts_sort_by");
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(eq(2L), eq("accounts_sort_by")))
            .thenReturn(Optional.of(nullValue));
        assertThat(service.listDefaults(2L, "accounts").sortBy()).isNull();

        Preference garbage = new Preference();
        garbage.setName("activity_event");
        garbage.setValue("@@@@");
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(eq(3L), eq("activity_event")))
            .thenReturn(Optional.of(garbage));
        assertThat(service.stringPreference(3L, "activity_event")).isEmpty();
    }

    private void stub(long userId, String name, String json) {
        Preference preference = new Preference();
        preference.setName(name);
        preference.setJsonValue(json);
        when(repository.findFirstByUserIdAndNameOrderByIdAsc(eq(userId), eq(name)))
            .thenReturn(Optional.of(preference));
    }
}
