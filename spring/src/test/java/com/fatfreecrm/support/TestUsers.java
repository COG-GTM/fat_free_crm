package com.fatfreecrm.support;

import com.fatfreecrm.domain.User;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds detached {@link User} instances for unit tests; the id is normally database-generated.
 */
public final class TestUsers {

    public static final Instant CONFIRMED_AT = Instant.parse("2020-01-01T00:00:00Z");

    private TestUsers() {
    }

    public static User activeUser(long id, String username, boolean admin) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername(username);
        user.setEmail(username.toLowerCase(java.util.Locale.ROOT) + "@example.com");
        user.setFirstName("Legacy");
        user.setLastName(username);
        user.setEncryptedPassword("0".repeat(128));
        user.setPasswordSalt("salt-salt-salt-salt-");
        user.setAdmin(admin);
        user.setConfirmedAt(CONFIRMED_AT);
        return user;
    }

    public static User unconfirmedUser(long id, String username) {
        User user = activeUser(id, username, false);
        user.setConfirmedAt(null);
        return user;
    }

    public static User suspendedUser(long id, String username) {
        User user = activeUser(id, username, false);
        user.setSuspendedAt(Instant.parse("2021-06-01T00:00:00Z"));
        return user;
    }
}
