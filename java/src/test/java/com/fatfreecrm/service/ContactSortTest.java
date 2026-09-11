package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class ContactSortTest {

    @Test
    void acceptsRailsValuesAndShorthands() {
        assertThat(ContactSort.parse("first_name ASC")).isEqualTo(ContactSort.FIRST_NAME_ASC);
        assertThat(ContactSort.parse("first_name")).isEqualTo(ContactSort.FIRST_NAME_ASC);
        assertThat(ContactSort.parse("last_name ASC")).isEqualTo(ContactSort.LAST_NAME_ASC);
        assertThat(ContactSort.parse("last_name")).isEqualTo(ContactSort.LAST_NAME_ASC);
        assertThat(ContactSort.parse("created_at DESC")).isEqualTo(ContactSort.CREATED_AT_DESC);
        assertThat(ContactSort.parse("created_at")).isEqualTo(ContactSort.CREATED_AT_DESC);
        assertThat(ContactSort.parse("updated_at DESC")).isEqualTo(ContactSort.UPDATED_AT_DESC);
        assertThat(ContactSort.parse("updated_at")).isEqualTo(ContactSort.UPDATED_AT_DESC);
    }

    @Test
    void blankIsDefaultCreatedAtDesc() {
        assertThat(ContactSort.parse(null)).isEqualTo(ContactSort.CREATED_AT_DESC);
        assertThat(ContactSort.parse("  ")).isEqualTo(ContactSort.CREATED_AT_DESC);
        assertThat(ContactSort.DEFAULT.sort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(ContactSort.DEFAULT.sort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void rejectsDirectionsRailsDoesNotOffer() {
        for (String bad : new String[] {"first_name DESC", "created_at ASC", "id", "email", "name ASC", "first_name asc"}) {
            assertThatThrownBy(() -> ContactSort.parse(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThat(Pattern.matches(ContactSort.PATTERN, bad)).as(bad).isFalse();
        }
    }

    @Test
    void railsValuesMatchTheContactSortableWhitelistInOrder() {
        assertThat(ContactSort.values()).extracting(ContactSort::railsValue)
                .containsExactly("first_name ASC", "last_name ASC", "created_at DESC", "updated_at DESC");
        assertThat(ContactSort.acceptedValues()).isEqualTo(
                "first_name ASC, first_name, last_name ASC, last_name, created_at DESC, created_at, "
                        + "updated_at DESC, updated_at");
    }

    @Test
    void everySortOrdersByItsColumnThenIdAscending() {
        assertSort(ContactSort.FIRST_NAME_ASC, "firstName", Sort.Direction.ASC);
        assertSort(ContactSort.LAST_NAME_ASC, "lastName", Sort.Direction.ASC);
        assertSort(ContactSort.CREATED_AT_DESC, "createdAt", Sort.Direction.DESC);
        assertSort(ContactSort.UPDATED_AT_DESC, "updatedAt", Sort.Direction.DESC);
    }

    @Test
    void surroundingWhitespaceIsToleratedButInnerWhitespaceIsNot() {
        assertThat(ContactSort.parse(" last_name ASC ")).isEqualTo(ContactSort.LAST_NAME_ASC);
        assertThat(ContactSort.parse("\tcreated_at\n")).isEqualTo(ContactSort.CREATED_AT_DESC);
        assertThatThrownBy(() -> ContactSort.parse("last_name  ASC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContactSort.parse("last_nameASC")).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertSort(ContactSort sort, String property, Sort.Direction direction) {
        assertThat(sort.sort().stream().toList()).containsExactly(
                new Sort.Order(direction, property), new Sort.Order(Sort.Direction.ASC, "id"));
    }

    @Test
    void validationPatternAcceptsExactlyTheParsableValues() {
        for (ContactSort sort : ContactSort.values()) {
            assertThat(Pattern.matches(ContactSort.PATTERN, sort.railsValue())).as(sort.railsValue()).isTrue();
            assertThat(Pattern.matches(ContactSort.PATTERN, sort.shortValue())).as(sort.shortValue()).isTrue();
        }
    }
}
