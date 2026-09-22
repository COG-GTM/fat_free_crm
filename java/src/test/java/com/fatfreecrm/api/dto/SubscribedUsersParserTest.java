package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SubscribedUsersParserTest {

    @Test
    void nullAndBlankBecomeEmptyList() {
        assertThat(SubscribedUsersParser.parse(null)).isEmpty();
        assertThat(SubscribedUsersParser.parse("")).isEmpty();
        assertThat(SubscribedUsersParser.parse("  \n ")).isEmpty();
    }

    @Test
    void parsesRailsBlockSequenceOfIntegers() {
        assertThat(SubscribedUsersParser.parse("---\n- 1\n- 2\n")).containsExactly(1L, 2L);
        assertThat(SubscribedUsersParser.parse("---\r\n- 7\r\n")).containsExactly(7L);
        assertThat(SubscribedUsersParser.parse("- 3\n- 4")).containsExactly(3L, 4L);
    }

    @Test
    void emptyYamlArrayBecomesEmptyList() {
        assertThat(SubscribedUsersParser.parse("--- []\n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("---\n")).isEmpty();
    }

    @Test
    void toleratesSurroundingWhitespaceAndBlankLines() {
        assertThat(SubscribedUsersParser.parse("---\n\n  - 5  \n\t- 6\n\n")).containsExactly(5L, 6L);
        assertThat(SubscribedUsersParser.parse("  ---  \n- 1\n")).containsExactly(1L);
    }

    @Test
    void integersOutsideLongRangeBecomeEmptyList() {
        assertThat(SubscribedUsersParser.parse("---\n- 99999999999999999999\n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("---\n- " + Long.MAX_VALUE + "\n")).containsExactly(Long.MAX_VALUE);
    }

    @Test
    void oneBadItemDiscardsTheWholeList() {
        assertThat(SubscribedUsersParser.parse("---\n- 1\n- 2\n- \n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("---\n- 1\n-2\n")).isEmpty();
    }

    @Test
    void documentMarkerIsOnlyRecognisedOnTheFirstLine() {
        assertThat(SubscribedUsersParser.parse("- 1\n---\n- 2\n")).isEmpty();
    }

    @Test
    void anythingElseBecomesEmptyList() {
        assertThat(SubscribedUsersParser.parse("---\n- alice\n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("---\n- 1\n- x\n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("--- !ruby/object:Foo\nbar: 1\n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("---\n- - 1\n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("---\nkey: value\n")).isEmpty();
        assertThat(SubscribedUsersParser.parse("[1, 2]")).isEmpty();
    }
}
