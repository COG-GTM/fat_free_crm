package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link GroupUser.Key} must behave as a JPA composite id: value equality and stable hashing. */
class GroupUserKeyTest {

    @Test
    void keysWithSameGroupAndUserAreEqualWithSameHash() {
        GroupUser.Key a = key(100L, 1L);
        GroupUser.Key b = key(100L, 1L);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isEqualTo(a);
    }

    @Test
    void keysDifferingInEitherComponentAreNotEqual() {
        GroupUser.Key base = key(100L, 1L);

        assertThat(base).isNotEqualTo(key(101L, 1L));
        assertThat(base).isNotEqualTo(key(100L, 2L));
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("100/1");
    }

    @Test
    void nullComponentsAreHandled() {
        assertThat(key(null, null)).isEqualTo(key(null, null));
        assertThat(key(null, 1L)).isNotEqualTo(key(100L, 1L));
    }

    private static GroupUser.Key key(Long groupId, Long userId) {
        GroupUser.Key key = new GroupUser.Key();
        key.setGroupId(groupId);
        key.setUserId(userId);
        return key;
    }
}
