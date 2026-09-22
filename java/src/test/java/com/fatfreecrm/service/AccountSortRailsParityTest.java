package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.Sort;

/**
 * Pins {@link AccountSort} to the Rails declaration in app/models/entities/account.rb:
 * <pre>
 * sortable by: ["name ASC", "rating DESC", "created_at DESC", "updated_at DESC"], default: "created_at DESC"
 * </pre>
 * The enum must expose exactly those orders, in that order, with that default, and every Rails
 * value must round-trip through {@link AccountSort#parse} and {@link AccountSort#PATTERN}.
 */
class AccountSortRailsParityTest {

    /** Verbatim copy of the Rails {@code sortable by:} array. */
    private static final List<String> RAILS_SORTABLE_BY =
            List.of("name ASC", "rating DESC", "created_at DESC", "updated_at DESC");

    private static final String RAILS_DEFAULT = "created_at DESC";

    @Test
    void enumConstantsAreExactlyTheRailsSortableValuesInDeclarationOrder() {
        assertThat(AccountSort.values()).extracting(AccountSort::railsValue).containsExactlyElementsOf(RAILS_SORTABLE_BY);
    }

    @Test
    void defaultIsTheRailsDefault() {
        assertThat(AccountSort.DEFAULT.railsValue()).isEqualTo(RAILS_DEFAULT);
        assertThat(AccountSort.parse(null)).isSameAs(AccountSort.DEFAULT);
    }

    @ParameterizedTest
    @EnumSource(AccountSort.class)
    void everyRailsValueRoundTripsThroughParseAndPattern(AccountSort sort) {
        assertThat(AccountSort.parse(sort.railsValue())).isSameAs(sort);
        assertThat(Pattern.compile(AccountSort.PATTERN).matcher(sort.railsValue()).matches()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AccountSort.class)
    void railsDirectionIsFixedAndOppositeDirectionIsRejected(AccountSort sort) {
        String[] parts = sort.railsValue().split(" ");
        String column = parts[0];
        String railsDirection = parts[1];
        String opposite = railsDirection.equals("ASC") ? "DESC" : "ASC";

        assertThat(sort.toSort().iterator().next().getDirection()).isEqualTo(Sort.Direction.fromString(railsDirection));
        assertThat(AccountSort.parse(column)).isSameAs(sort);
        assertThatIllegalArgumentException().isThrownBy(() -> AccountSort.parse(column + " " + opposite));
        assertThat(Pattern.compile(AccountSort.PATTERN).matcher(column + " " + opposite).matches()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(AccountSort.class)
    void sortHasTheRailsColumnFollowedOnlyByTheIdTiebreaker(AccountSort sort) {
        List<Sort.Order> orders = sort.toSort().toList();

        assertThat(orders).hasSize(2);
        assertThat(orders.get(1).getProperty()).isEqualTo("id");
        assertThat(orders.get(1).getDirection()).isEqualTo(orders.get(0).getDirection());
    }

    @Test
    void patternMessageListsTheRailsValues() {
        assertThat(AccountSort.PATTERN_MESSAGE).isEqualTo("must be one of: " + String.join(", ", RAILS_SORTABLE_BY));
    }
}
