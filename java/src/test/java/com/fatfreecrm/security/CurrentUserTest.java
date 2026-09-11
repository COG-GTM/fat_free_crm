package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Invariants of the {@link CurrentUser} principal record. */
class CurrentUserTest {

    @Test
    void nullIdIsRejected() {
        assertThatThrownBy(() -> new CurrentUser(null, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void nullGroupIdsBecomeEmptySet() {
        CurrentUser user = new CurrentUser(1L, false, null);

        assertThat(user.groupIds()).isNotNull().isEmpty();
    }

    @Test
    void groupIdsAreDefensivelyCopiedAndImmutable() {
        Set<Long> mutable = new HashSet<>(Set.of(100L, 101L));
        CurrentUser user = new CurrentUser(1L, false, mutable);

        mutable.add(102L);

        assertThat(user.groupIds()).containsExactlyInAnyOrder(100L, 101L);
        assertThatThrownBy(() -> user.groupIds().add(103L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void adminFlagAndIdAreExposed() {
        CurrentUser user = new CurrentUser(42L, true, Set.of(7L));

        assertThat(user.id()).isEqualTo(42L);
        assertThat(user.admin()).isTrue();
        assertThat(user.groupIds()).containsExactly(7L);
    }
}
