package com.fatfreecrm.api.dto;

import static com.fatfreecrm.api.dto.ContactMapper.parseSubscribedUsers;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContactMapperTest {

    @Test
    void nullBlankAndEmptyArrayYieldEmptyList() {
        assertThat(parseSubscribedUsers(null)).isEmpty();
        assertThat(parseSubscribedUsers("")).isEmpty();
        assertThat(parseSubscribedUsers("   \n")).isEmpty();
        assertThat(parseSubscribedUsers("--- []\n")).isEmpty();
        assertThat(parseSubscribedUsers("---\n")).isEmpty();
    }

    @Test
    void parsesRailsIntegerArray() {
        assertThat(parseSubscribedUsers("---\n- 1\n- 2\n")).containsExactly(1L, 2L);
        assertThat(parseSubscribedUsers("---\r\n- 7\r\n")).containsExactly(7L);
        assertThat(parseSubscribedUsers("---\n- 3\n\n- 40 \n")).containsExactly(3L, 40L);
    }

    @Test
    void anythingElseYieldsEmptyList() {
        assertThat(parseSubscribedUsers("- 1\n- 2\n")).isEmpty();
        assertThat(parseSubscribedUsers("---\n- foo\n")).isEmpty();
        assertThat(parseSubscribedUsers("---\n- 1\n- - 2\n")).isEmpty();
        assertThat(parseSubscribedUsers("---\n:a: 1\n")).isEmpty();
        assertThat(parseSubscribedUsers("--- !ruby/object:Set\nhash: {}\n")).isEmpty();
        assertThat(parseSubscribedUsers("[1, 2]")).isEmpty();
    }
}
