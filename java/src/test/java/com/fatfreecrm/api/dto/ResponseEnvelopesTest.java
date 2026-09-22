package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The list/autocomplete envelopes never expose a {@code null} or externally mutable collection. */
class ResponseEnvelopesTest {

    @Test
    void pageResponseTurnsNullItemsIntoEmptyList() {
        PageResponse<String> response = new PageResponse<>(null, 1, 20, 0);

        assertThat(response.items()).isNotNull().isEmpty();
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.perPage()).isEqualTo(20);
        assertThat(response.totalCount()).isZero();
    }

    @Test
    void pageResponseCopiesItems() {
        List<String> items = new ArrayList<>(List.of("a", "b"));
        PageResponse<String> response = new PageResponse<>(items, 2, 2, 5);
        items.add("c");

        assertThat(response.items()).containsExactly("a", "b");
        assertThatThrownBy(() -> response.items().add("d")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void autoCompleteResponseTurnsNullResultsIntoEmptyList() {
        assertThat(new AutoCompleteResponse(null).results()).isNotNull().isEmpty();
    }

    @Test
    void autoCompleteResponseCopiesResults() {
        List<AutoCompleteResponse.Item> results = new ArrayList<>();
        results.add(new AutoCompleteResponse.Item(1, "Acme"));
        AutoCompleteResponse response = new AutoCompleteResponse(results);
        results.clear();

        assertThat(response.results()).containsExactly(new AutoCompleteResponse.Item(1, "Acme"));
        assertThatThrownBy(() -> response.results().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
