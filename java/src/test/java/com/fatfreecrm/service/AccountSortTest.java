package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;

class AccountSortTest {

    private static final Pattern PATTERN = Pattern.compile(AccountSort.PATTERN);

    @ParameterizedTest
    @CsvSource({
            "name ASC, NAME_ASC",
            "name, NAME_ASC",
            "NAME asc, NAME_ASC",
            "rating DESC, RATING_DESC",
            "rating, RATING_DESC",
            "created_at DESC, CREATED_AT_DESC",
            "created_at, CREATED_AT_DESC",
            "createdAt, CREATED_AT_DESC",
            "createdAt DESC, CREATED_AT_DESC",
            "updated_at DESC, UPDATED_AT_DESC",
            "updated_at, UPDATED_AT_DESC",
            "updatedAt, UPDATED_AT_DESC",
            "'  updated_at   desc ', UPDATED_AT_DESC",
    })
    void parsesRailsValuesAndAliases(String input, AccountSort expected) {
        assertThat(AccountSort.parse(input)).isEqualTo(expected);
        assertThat(PATTERN.matcher(input).matches()).as("PATTERN accepts %s", input).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankIsDefault(String input) {
        assertThat(AccountSort.parse(input)).isEqualTo(AccountSort.CREATED_AT_DESC);
        assertThat(AccountSort.parse(null)).isEqualTo(AccountSort.CREATED_AT_DESC);
        assertThat(PATTERN.matcher(input).matches()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "name DESC", "rating ASC", "created_at ASC", "id", "name ASC extra", "name; drop"})
    void rejectsUnknownOrdersConsistentlyWithPattern(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountSort.parse(input));
        assertThat(PATTERN.matcher(input).matches()).as("PATTERN rejects %s", input).isFalse();
    }

    /** app/models/entities/account.rb: {@code sortable by: [...], default: "created_at DESC"}. */
    @Test
    void railsValuesMatchTheAccountSortableDeclarationInOrder() {
        assertThat(AccountSort.values()).extracting(AccountSort::railsValue)
                .containsExactly("name ASC", "rating DESC", "created_at DESC", "updated_at DESC");
        assertThat(AccountSort.DEFAULT.railsValue()).isEqualTo("created_at DESC");
        assertThat(AccountSort.PATTERN_MESSAGE)
                .isEqualTo("must be one of: name ASC, rating DESC, created_at DESC, updated_at DESC");
    }

    @Test
    void everyRailsValueRoundTripsThroughParse() {
        for (AccountSort sort : AccountSort.values()) {
            assertThat(AccountSort.parse(sort.railsValue())).isEqualTo(sort);
            assertThat(PATTERN.matcher(sort.railsValue()).matches()).isTrue();
        }
    }

    @Test
    void unknownOrderMessageNamesTheValueAndTheAllowedList() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountSort.parse("email"))
                .withMessage("sortBy 'email' " + AccountSort.PATTERN_MESSAGE);
    }

    @Test
    void sortNeverContainsMoreThanTheColumnAndTheIdTiebreaker() {
        for (AccountSort sort : AccountSort.values()) {
            assertThat(sort.toSort()).hasSize(2);
            assertThat(sort.toSort().stream().reduce((a, b) -> b).orElseThrow().getProperty()).isEqualTo("id");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "NAME_ASC, name, ASC",
            "RATING_DESC, rating, DESC",
            "CREATED_AT_DESC, createdAt, DESC",
            "UPDATED_AT_DESC, updatedAt, DESC",
    })
    void sortUsesEntityPropertyWithIdTiebreaker(AccountSort sort, String property, Sort.Direction direction) {
        Sort.Order first = sort.toSort().iterator().next();
        assertThat(first.getProperty()).isEqualTo(property);
        assertThat(first.getDirection()).isEqualTo(direction);
        assertThat(sort.toSort().getOrderFor("id")).isNotNull();
        assertThat(sort.toSort().getOrderFor("id").getDirection()).isEqualTo(direction);
    }
}
