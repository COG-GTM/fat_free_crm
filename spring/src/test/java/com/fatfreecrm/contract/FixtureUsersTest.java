package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FixtureUsersTest {
    @Test
    void loadsTheFiveDeterministicFixtureUsers() throws Exception {
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        assertEquals(Set.of("admin", "alice", "bob", "sam", "carol"), users.keySet());
        assertEquals(Set.of(1, 2, 3, 4, 5),
            users.values().stream().map(FixtureUsers.FixtureUser::id).collect(Collectors.toSet()));
        users.forEach((key, user) -> {
            assertEquals(key, user.key());
            assertEquals(key, user.username());
            assertEquals(key + "@contract.example", user.email());
            assertEquals("contract-password", user.password());
        });
    }

    @Test
    void defaultsAdminAndSuspendedFlagsToFalse() throws Exception {
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        FixtureUsers.FixtureUser admin = users.get("admin");
        assertEquals(1, admin.id());
        assertTrue(admin.admin());
        assertFalse(admin.suspended());
        FixtureUsers.FixtureUser sam = users.get("sam");
        assertEquals(4, sam.id());
        assertTrue(sam.suspended());
        assertFalse(sam.admin());
        assertFalse(users.get("alice").admin());
        assertFalse(users.get("alice").suspended());
        assertEquals(1, users.values().stream().filter(FixtureUsers.FixtureUser::admin).count());
        assertEquals(1, users.values().stream().filter(FixtureUsers.FixtureUser::suspended).count());
    }

    @Test
    void returnsAnImmutableMap() throws Exception {
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        assertThrows(UnsupportedOperationException.class, () -> users.remove("alice"));
    }
}
