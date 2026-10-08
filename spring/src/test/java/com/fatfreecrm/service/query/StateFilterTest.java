package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StateFilterTest {

    private final StateFilter filter = new StateFilter("category", (root, builder, values) -> null);

    @Test
    void absentOrEmptyFilterYieldsNoValuesSoNoPredicateIsAdded() {
        assertThat(filter.values(null)).isEmpty();
        assertThat(filter.values("")).isEmpty();
    }

    @Test
    void splitsRailsCommaSeparatedFilterKeepingOrderDuplicatesAndBlankSegments() {
        assertThat(filter.values("customer,vendor")).containsExactly("customer", "vendor");
        assertThat(filter.values("other")).containsExactly("other");
        assertThat(filter.values("customer,,vendor,customer")).containsExactly("customer", "", "vendor", "customer");
        assertThat(filter.values(" customer , vendor")).containsExactly(" customer ", " vendor");
        assertThat(filter.values(",")).isEmpty();
    }
}
