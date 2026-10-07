package com.fatfreecrm.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubscribedUsersConverterTest {

    private final SubscribedUsersConverter converter = new SubscribedUsersConverter();

    @Test
    void writesTheExactPsychArrayDocumentRailsWrites() {
        assertThat(converter.convertToDatabaseColumn(List.of(1L))).isEqualTo("---\n- 1\n");
        assertThat(converter.convertToDatabaseColumn(List.of(Long.MAX_VALUE, 0L, -1L)))
            .isEqualTo("---\n- 9223372036854775807\n- 0\n- -1\n");
    }

    @Test
    void roundTripsOrderAndDuplicatesWithoutNormalizing() {
        List<Long> ids = List.of(5L, 5L, 2L);
        String column = converter.convertToDatabaseColumn(ids);
        assertThat(column).isEqualTo("---\n- 5\n- 5\n- 2\n");
        assertThat(converter.convertToEntityAttribute(column)).containsExactlyElementsOf(ids);
    }

    @Test
    void readsFlowStyleAndTaggedArraysFromOtherPsychVersions() {
        assertThat(converter.convertToEntityAttribute("--- []")).isEmpty();
        assertThat(converter.convertToEntityAttribute("---\n[]\n")).isEmpty();
        assertThat(converter.convertToEntityAttribute("--- [3, 1]\n")).containsExactly(3L, 1L);
        assertThat(converter.convertToEntityAttribute("--- !ruby/array:Array\n- 7\n")).containsExactly(7L);
        assertThat(converter.convertToEntityAttribute("---\n- 9223372036854775807\n"))
            .containsExactly(Long.MAX_VALUE);
    }

    @Test
    void readsIntoAMutableListSoEntitiesCanAppendSubscribers() {
        List<Long> ids = converter.convertToEntityAttribute("---\n- 1\n");
        ids.add(2L);
        assertThat(ids).containsExactly(1L, 2L);
        List<Long> empty = converter.convertToEntityAttribute(null);
        empty.add(3L);
        assertThat(empty).containsExactly(3L);
    }

    @Test
    void rejectsTheLegacySetEncodingAndNonArrayDocuments() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("--- !ruby/object:Set\nhash:\n  1: true\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected subscribed_users YAML sequence");
        assertThatThrownBy(() -> converter.convertToEntityAttribute("--- 1\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected subscribed_users YAML sequence");
        assertThatThrownBy(() -> converter.convertToEntityAttribute("---\nuser_id: 1\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected subscribed_users YAML sequence");
        assertThatThrownBy(() -> converter.convertToEntityAttribute("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Expected subscribed_users YAML sequence");
    }

    @Test
    void rejectsItemsThatCannotBeRailsUserIds() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("---\n- 9223372036854775808\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid subscribed user id: 9223372036854775808");
        assertThatThrownBy(() -> converter.convertToEntityAttribute("---\n- \n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid subscribed user id: null");
        assertThatThrownBy(() -> converter.convertToEntityAttribute("---\n- true\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid subscribed user id: true");
        assertThatThrownBy(() -> converter.convertToEntityAttribute("---\n- - 1\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid subscribed user id: [1]");
    }
}
