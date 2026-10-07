package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** AB-270 additions to {@link ListQuery}: the {@code filter} component and its copy methods. */
class ListQueryFilterTest {

    private static MultiValueMap<String, String> params(String... entries) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (String entry : entries) {
            int equals = entry.indexOf('=');
            params.add(entry.substring(0, equals), entry.substring(equals + 1));
        }
        return params;
    }

    @Test
    void parsedQueriesStartWithoutFilterOrPreferences() {
        ListQuery query = ListQuery.fromParameters(params("page=1", "category=customer"));

        assertThat(query.filter()).isNull();
        assertThat(query.preferredPerPage()).isNull();
        assertThat(query.preferredSortBy()).isNull();
        assertThat(query.q()).isEmpty();
    }

    @Test
    void legacySevenArgumentConstructorLeavesFilterNull() {
        ListQuery query = new ListQuery("1", "5", "acme", "name", Map.of("name_cont", "a"), 10, "accounts.name ASC");

        assertThat(query.filter()).isNull();
        assertThat(query.preferredPerPage()).isEqualTo(10);
        assertThat(query.preferredSortBy()).isEqualTo("accounts.name ASC");
    }

    @Test
    void withFilterKeepsEveryOtherComponent() {
        ListQuery base = ListQuery.fromParameters(params(
            "page=2", "per_page=5", "query=acme", "sort_by=name", "q[name_cont]=x"))
            .withPreferences(7, "accounts.rating DESC");

        ListQuery filtered = base.withFilter("customer,other");

        assertThat(filtered.filter()).isEqualTo("customer,other");
        assertThat(filtered.page()).isEqualTo("2");
        assertThat(filtered.perPage()).isEqualTo("5");
        assertThat(filtered.query()).isEqualTo("acme");
        assertThat(filtered.sortBy()).isEqualTo("name");
        assertThat(filtered.q()).isEqualTo(Map.of("name_cont", "x"));
        assertThat(filtered.preferredPerPage()).isEqualTo(7);
        assertThat(filtered.preferredSortBy()).isEqualTo("accounts.rating DESC");
        assertThat(base.filter()).isNull();
    }

    @Test
    void withPreferencesKeepsAnExistingFilter() {
        ListQuery query = ListQuery.fromParameters(params("per_page=3"))
            .withFilter("other")
            .withPreferences(20, null);

        assertThat(query.filter()).isEqualTo("other");
        assertThat(query.preferredPerPage()).isEqualTo(20);
        assertThat(query.preferredSortBy()).isNull();
        assertThat(query.perPage()).isEqualTo("3");
    }

    @Test
    void copiesAreIndependentAndAcceptNullFilter() {
        ListQuery filtered = ListQuery.fromParameters(params()).withFilter("customer");
        ListQuery cleared = filtered.withFilter(null);

        assertThat(filtered.filter()).isEqualTo("customer");
        assertThat(cleared.filter()).isNull();
        assertThat(cleared).isEqualTo(ListQuery.fromParameters(params()));
    }
}
