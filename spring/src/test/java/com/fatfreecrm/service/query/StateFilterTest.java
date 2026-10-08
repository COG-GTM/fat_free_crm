package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link StateFilter#values(String)} to Ruby {@code String#split(",")} as used by the Rails
 * {@code state} filters (e.g. {@code @state.split(",")} in {@code Opportunity.state} /
 * {@code Campaign.state}).
 */
class StateFilterTest {

    private static final StateFilter FILTER = new StateFilter("state", (root, builder, values) -> null);

    @Test
    void nullAndEmptyProduceNoValuesLikeRubyNilAndEmptySplit() {
        assertThat(FILTER.values(null)).isEmpty();
        assertThat(FILTER.values("")).isEmpty();
    }

    @Test
    void splitsOnCommaPreservingOrder() {
        assertThat(FILTER.values("prospecting,won")).containsExactly("prospecting", "won");
    }

    @Test
    void trailingCommaIsDroppedLikeRubySplit() {
        // "a,".split(",") => ["a"] in Ruby
        assertThat(FILTER.values("won,")).containsExactly("won");
        assertThat(FILTER.values("won,,")).containsExactly("won");
    }

    @Test
    void embeddedEmptyValueIsKeptLikeRubySplit() {
        // "a,,b".split(",") => ["a", "", "b"] in Ruby
        assertThat(FILTER.values("won,,lost")).containsExactly("won", "", "lost");
    }

    @Test
    void leadingCommaKeepsEmptyFirstValueLikeRubySplit() {
        // ",a".split(",") => ["", "a"] in Ruby
        assertThat(FILTER.values(",won")).containsExactly("", "won");
    }

    @Test
    void doesNotTrimWhitespaceLikeRubySplit() {
        assertThat(FILTER.values("won, lost")).containsExactly("won", " lost");
    }

    @Test
    void returnedListCannotGrow() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FILTER.values("won").add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
