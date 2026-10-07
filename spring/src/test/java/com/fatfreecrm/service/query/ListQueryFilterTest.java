package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** AB-270 additions to {@link ListQuery}: the session-style {@code filter} and preference copies. */
class ListQueryFilterTest {

    private static ListQuery parsed() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("page", "2");
        params.add("per_page", "5");
        params.add("query", "acme");
        params.add("sort_by", "name");
        params.add("q[name_cont]", "x");
        return ListQuery.fromParameters(params);
    }

    @Test
    void parsedQueriesStartWithoutFilterOrPreferences() {
        ListQuery query = parsed();
        assertThat(query.filter()).isNull();
        assertThat(query.preferredPerPage()).isNull();
        assertThat(query.preferredSortBy()).isNull();
    }

    @Test
    void legacyConstructorLeavesFilterNull() {
        ListQuery query = new ListQuery("1", "20", null, null, Map.of(), 7, "accounts.name ASC");
        assertThat(query.filter()).isNull();
        assertThat(query.preferredPerPage()).isEqualTo(7);
        assertThat(query.preferredSortBy()).isEqualTo("accounts.name ASC");
    }

    @Test
    void withFilterCopiesEveryOtherField() {
        ListQuery query = parsed().withPreferences(3, "accounts.name ASC").withFilter("customer,other");

        assertThat(query.filter()).isEqualTo("customer,other");
        assertThat(query.page()).isEqualTo("2");
        assertThat(query.perPage()).isEqualTo("5");
        assertThat(query.query()).isEqualTo("acme");
        assertThat(query.sortBy()).isEqualTo("name");
        assertThat(query.q()).isEqualTo(Map.of("name_cont", "x"));
        assertThat(query.preferredPerPage()).isEqualTo(3);
        assertThat(query.preferredSortBy()).isEqualTo("accounts.name ASC");
    }

    @Test
    void withPreferencesKeepsAnExistingFilter() {
        ListQuery query = parsed().withFilter("other").withPreferences(2, null);

        assertThat(query.filter()).isEqualTo("other");
        assertThat(query.preferredPerPage()).isEqualTo(2);
        assertThat(query.preferredSortBy()).isNull();
    }

    @Test
    void withFilterNullClearsTheFilterWithoutTouchingTheRest() {
        ListQuery query = parsed().withFilter("customer").withFilter(null);
        assertThat(query.filter()).isNull();
        assertThat(query.q()).isEqualTo(Map.of("name_cont", "x"));
    }
}
