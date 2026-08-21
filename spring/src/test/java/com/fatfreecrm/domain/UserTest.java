package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link User#isActive()} to the Rails {@code User#suspended?} /
 * paranoia soft-delete semantics: a user with either {@code suspended_at} or
 * {@code deleted_at} set must not be able to sign in.
 */
class UserTest {

    @Test
    void isActiveWhenNeitherSuspendedNorDeleted() {
        assertThat(new User().isActive()).isTrue();
    }

    @Test
    void isInactiveWhenSuspended() {
        User user = new User();
        user.setSuspendedAt(Instant.now());
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void isInactiveWhenSoftDeleted() {
        User user = new User();
        user.setDeletedAt(Instant.now());
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void isInactiveWhenBothSuspendedAndDeleted() {
        User user = new User();
        user.setSuspendedAt(Instant.now());
        user.setDeletedAt(Instant.now());
        assertThat(user.isActive()).isFalse();
    }
}
