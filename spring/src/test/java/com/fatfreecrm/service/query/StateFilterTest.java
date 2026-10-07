package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rails: {@code session[:accounts_filter].to_s.split(',')}, applied only when {@code present?}.
 * Ruby's {@code split} drops trailing empty strings but keeps leading ones.
 */
class StateFilterTest {

    private final StateFilter filter = new StateFilter("category", (root, builder, values) -> null);

    @Test
    void nullAndEmptyRawValuesProduceNoFilter() {
        assertThat(filter.values(null)).isEmpty();
        assertThat(filter.values("")).isEmpty();
    }

    @Test
    void splitsCommaSeparatedValuesLikeRubySplit() {
        assertThat(filter.values("customer")).containsExactly("customer");
        assertThat(filter.values("customer,other")).containsExactly("customer", "other");
        assertThat(filter.values("customer,")).containsExactly("customer");
        assertThat(filter.values(",customer")).containsExactly("", "customer");
        assertThat(filter.values("a,,b")).containsExactly("a", "", "b");
    }

    @Test
    void doesNotTrimWhitespaceAroundValues() {
        assertThat(filter.values("customer, other")).containsExactly("customer", " other");
    }

    @Test
    void exposesParameterNameAndPredicate() {
        StateFilter.StatePredicate predicate = (root, builder, values) -> null;
        StateFilter named = new StateFilter("category", predicate);

        assertThat(named.param()).isEqualTo("category");
        assertThat(named.predicate()).isSameAs(predicate);
        assertThat(named.values("x")).isEqualTo(List.of("x"));
    }
}
