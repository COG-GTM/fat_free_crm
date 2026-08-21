package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link User#isActive()} to the Rails behavior: {@code User#suspended?}
 * is {@code suspended_at != nil}, and a soft-deleted user ({@code deleted_at}
 * set) cannot authenticate either.
 */
class UserActiveStateTest {

    @Test
    void freshUserIsActive() {
        assertThat(new User().isActive()).isTrue();
    }

    @Test
    void suspendedUserIsNotActive() {
        User user = new User();
        user.setSuspendedAt(Instant.now());
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void softDeletedUserIsNotActive() {
        User user = new User();
        user.setDeletedAt(Instant.now());
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void suspendedAndDeletedUserIsNotActive() {
        User user = new User();
        user.setSuspendedAt(Instant.now());
        user.setDeletedAt(Instant.now());
        assertThat(user.isActive()).isFalse();
    }

    @Test
    void clearingSuspensionReactivatesTheUser() {
        User user = new User();
        user.setSuspendedAt(Instant.now());
        user.setSuspendedAt(null);
        assertThat(user.isActive()).isTrue();
    }
}
