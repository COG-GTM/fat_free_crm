package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code session[:accounts_filter].to_s.split(',')} parity for the {@code category} list filter. */
class StateFilterTest {

    private final StateFilter filter = new StateFilter("category", (root, builder, values) -> null);

    @Test
    void nullAndEmptyFiltersProduceNoValues() {
        assertThat(filter.values(null)).isEmpty();
        assertThat(filter.values("")).isEmpty();
    }

    @Test
    void splitsOnCommasKeepingBlankSegmentsLikeRubySplit() {
        assertThat(filter.values("customer")).containsExactly("customer");
        assertThat(filter.values("customer,other")).containsExactly("customer", "other");
        assertThat(filter.values(",customer")).containsExactly("", "customer");
        assertThat(filter.values("customer, other")).containsExactly("customer", " other");
    }

    @Test
    void trailingCommasAreDroppedLikeRubySplit() {
        assertThat(filter.values(",,")).isEmpty();
        assertThat(filter.values("customer,")).containsExactly("customer");
    }

    @Test
    void exposesTheRequestParameterName() {
        assertThat(filter.param()).isEqualTo("category");
    }
}
