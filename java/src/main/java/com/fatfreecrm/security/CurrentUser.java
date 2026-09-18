package com.fatfreecrm.security;

import java.util.Set;

/**
 * The authenticated principal: the Rails {@code users.id}, the {@code users.admin} flag and the
 * ids of the groups the user belongs to ({@code groups_users}). Group ids are resolved once per
 * request so the access-control Specification can be built without further lookups.
 *
 * @param id       {@code users.id}
 * @param admin    {@code users.admin} — admins see every record (see Rails {@code Ability})
 * @param groupIds ids from {@code groups_users} for this user; never {@code null}
 */
public record CurrentUser(Long id, boolean admin, Set<Long> groupIds) {

    public CurrentUser {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }
}
