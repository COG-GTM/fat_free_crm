package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void nullItemsBecomeEmptyList() {
        PageResponse<String> response = new PageResponse<>(null, 1, 20, 0);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.perPage()).isEqualTo(20);
        assertThat(response.totalCount()).isZero();
    }

    @Test
    void itemsAreCopiedAndImmutable() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        PageResponse<String> response = new PageResponse<>(source, 2, 2, 5);
        source.add("c");

        assertThat(response.items()).containsExactly("a", "b");
        assertThatThrownBy(() -> response.items().add("d")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void totalCountMayExceedItemsOnThePage() {
        PageResponse<String> response = new PageResponse<>(List.of("a"), 3, 1, 123);

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalCount()).isEqualTo(123);
    }
}
