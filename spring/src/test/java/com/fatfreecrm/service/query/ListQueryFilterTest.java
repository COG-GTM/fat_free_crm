package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The AB-270 {@code filter} slot added to {@link ListQuery} (Rails {@code params[:category]} / {@code [:status]}
 * / {@code [:stage]}) must compose with the existing preference overlay without dropping either side, and the
 * pre-existing seven-argument constructor must keep working for callers that have no filter.
 */
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
    void parsedQueriesStartWithoutAFilter() {
        ListQuery query = ListQuery.fromParameters(params("page=1"));
        assertThat(query.filter()).isNull();
        assertThat(new ListQuery("1", "20", "acme", "name", Map.of(), 10, "name").filter()).isNull();
    }

    @Test
    void withFilterKeepsEveryOtherSlot() {
        ListQuery base = ListQuery.fromParameters(params(
            "page=3", "per_page=7", "query=acme", "sort_by=rating", "q[name_cont]=x"))
            .withPreferences(50, "accounts.name ASC");

        ListQuery filtered = base.withFilter("Affiliate,Customer");

        assertThat(filtered.filter()).isEqualTo("Affiliate,Customer");
        assertThat(filtered.page()).isEqualTo("3");
        assertThat(filtered.perPage()).isEqualTo("7");
        assertThat(filtered.query()).isEqualTo("acme");
        assertThat(filtered.sortBy()).isEqualTo("rating");
        assertThat(filtered.q()).isEqualTo(Map.of("name_cont", "x"));
        assertThat(filtered.preferredPerPage()).isEqualTo(50);
        assertThat(filtered.preferredSortBy()).isEqualTo("accounts.name ASC");
        assertThat(base.filter()).as("records are immutable").isNull();
    }

    @Test
    void withPreferencesAfterWithFilterKeepsTheFilter() {
        ListQuery query = ListQuery.fromParameters(params("page=2"))
            .withFilter("new,contacted")
            .withPreferences(25, "leads.first_name ASC");

        assertThat(query.filter()).isEqualTo("new,contacted");
        assertThat(query.preferredPerPage()).isEqualTo(25);
        assertThat(query.preferredSortBy()).isEqualTo("leads.first_name ASC");
        assertThat(query.page()).isEqualTo("2");
    }

    @Test
    void stateFilterSplitsRailsCommaListsAndTreatsBlankAsNoFilter() {
        StateFilter filter = new StateFilter("category", (root, builder, values) -> null);

        assertThat(filter.values(null)).isEmpty();
        assertThat(filter.values("")).isEmpty();
        assertThat(filter.values("Customer")).containsExactly("Customer");
        assertThat(filter.values("Affiliate,Customer,Partner")).containsExactly("Affiliate", "Customer", "Partner");
        assertThat(filter.values("Affiliate,,Customer")).as("Rails String#split keeps interior empties")
            .containsExactly("Affiliate", "", "Customer");
        assertThat(filter.values("Affiliate,")).as("Rails String#split drops trailing empties")
            .containsExactly("Affiliate");
        assertThat(filter.values(" Affiliate , Customer")).as("values are not trimmed, matching Rails")
            .isEqualTo(List.of(" Affiliate ", " Customer"));
    }
}
