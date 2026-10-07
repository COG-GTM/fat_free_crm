package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** {@code q[...]} bracket parsing into a nested tree, plus scalar parameter extraction. */
class ListQueryTest {

    private static MultiValueMap<String, String> params(String... entries) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (String entry : entries) {
            int equals = entry.indexOf('=');
            params.add(entry.substring(0, equals), entry.substring(equals + 1));
        }
        return params;
    }

    @Test
    void scalarParametersAreExtracted() {
        ListQuery query = ListQuery.fromParameters(params(
            "page=2", "per_page=5", "query=acme", "sort_by=name", "q[name_cont]=x"));
        assertThat(query.page()).isEqualTo("2");
        assertThat(query.perPage()).isEqualTo("5");
        assertThat(query.query()).isEqualTo("acme");
        assertThat(query.sortBy()).isEqualTo("name");
        assertThat(query.q()).isEqualTo(Map.of("name_cont", "x"));
    }

    @Test
    void listKeysAndRepeatedKeysBecomeLists() {
        ListQuery query = ListQuery.fromParameters(params(
            "q[rating_in][]=2", "q[rating_in][]=3", "q[rating_in][]=4"));
        assertThat(query.q()).isEqualTo(Map.of("rating_in", List.of("2", "3", "4")));
    }

    @Test
    void groupAndConditionTreesNest() {
        ListQuery query = ListQuery.fromParameters(params(
            "q[g][0][m]=or", "q[g][0][name_cont]=a",
            "q[c][0][a][0][name]=name", "q[c][0][p]=cont", "q[c][0][v][0][value]=foo"));
        Map<String, Object> q = query.q();
        assertThat(q.get("g")).isEqualTo(Map.of("0", Map.of("m", "or", "name_cont", "a")));
        assertThat(q.get("c")).isEqualTo(Map.of(
            "0", Map.of("a", Map.of("0", Map.of("name", "name")), "p", "cont",
                "v", Map.of("0", Map.of("value", "foo")))));
    }

    @Test
    void sortEntriesAcceptScalarAndList() {
        assertThat(ListQuery.fromParameters(params("q[s]=name desc")).q())
            .isEqualTo(Map.of("s", "name desc"));
        assertThat(ListQuery.fromParameters(params("q[s][]=name desc", "q[s][]=rating asc")).q())
            .isEqualTo(Map.of("s", List.of("name desc", "rating asc")));
    }

    @Test
    void withPreferencesOnlyFillsAbsentValues() {
        ListQuery base = ListQuery.fromParameters(params("per_page=5"));
        ListQuery preferred = base.withPreferences(10, "name");
        assertThat(preferred.perPage()).isEqualTo("5");
        assertThat(preferred.preferredPerPage()).isEqualTo(10);
        assertThat(preferred.preferredSortBy()).isEqualTo("name");
    }
}
