package com.fatfreecrm.support;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcTemplate-based fixtures for the phase-1 tables. Rows are inserted with explicit ids so
 * tests can reference them; timestamps default to {@code now()} unless a method takes them.
 * Access levels are the Rails strings {@code Public} / {@code Private} / {@code Shared}.
 * Registered by {@link TestSupportConfig}, which {@code AbstractIntegrationTest} imports.
 */
public class TestDataSeeder {

    private final JdbcTemplate jdbc;

    public TestDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Deletes every row from the phase-1 tables and resets identity sequences. */
    public void truncateAll() {
        jdbc.execute("TRUNCATE TABLE permissions, groups_users, groups, accounts, contacts, users RESTART IDENTITY");
    }

    // ---- users / groups -------------------------------------------------------------------

    public long insertUser(long id, String username, boolean admin) {
        return insertUser(id, username, admin, null);
    }

    public long insertUser(long id, String username, boolean admin, LocalDateTime suspendedAt) {
        jdbc.update("""
                INSERT INTO users (id, username, email, admin, suspended_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """, id, username, username + "@example.com", admin, ts(suspendedAt));
        return id;
    }

    public void softDeleteUser(long id) {
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", id);
    }

    public long insertGroup(long id, String name) {
        jdbc.update("INSERT INTO groups (id, name, created_at, updated_at) VALUES (?, ?, now(), now())", id, name);
        return id;
    }

    public void addUserToGroup(long userId, long groupId) {
        jdbc.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", groupId, userId);
    }

    // ---- permissions ----------------------------------------------------------------------

    /**
     * @param assetType Rails class name, e.g. {@code "Account"}
     * @param userId    grantee user, or {@code null} for a group grant
     * @param groupId   grantee group, or {@code null} for a user grant
     */
    public long insertPermission(String assetType, long assetId, Long userId, Long groupId) {
        return jdbc.queryForObject("""
                INSERT INTO permissions (user_id, asset_type, asset_id, group_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now()) RETURNING id
                """, Long.class, userId, assetType, assetId, groupId);
    }

    // ---- accounts -------------------------------------------------------------------------

    public long insertAccount(long id, String name, Long userId, Long assignedTo, String access) {
        return insertAccount(id, name, userId, assignedTo, access, null);
    }

    /** @param deletedAt non-null to seed a soft-deleted row */
    public long insertAccount(long id, String name, Long userId, Long assignedTo, String access, LocalDateTime deletedAt) {
        jdbc.update("""
                INSERT INTO accounts (id, name, user_id, assigned_to, access, rating, category, email, phone,
                                      website, deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, NULL, NULL, NULL, NULL, ?, now(), now())
                """, id, name, userId, assignedTo, access, ts(deletedAt));
        return id;
    }

    /** Full-column variant for tests that need to assert DTO field mapping. */
    public long insertAccount(AccountRow row) {
        jdbc.update("""
                INSERT INTO accounts (id, user_id, assigned_to, name, access, website, toll_free_phone, phone, fax,
                                      email, background_info, rating, category, subscribed_users, contacts_count,
                                      opportunities_count, wikidata_id, latitude, longitude, blog, linkedin, facebook,
                                      twitter, bluesky, instagram, mastodon, deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(), row.userId(), row.assignedTo(), row.name(), row.access(), row.website(), row.tollFreePhone(),
                row.phone(), row.fax(), row.email(), row.backgroundInfo(), row.rating(), row.category(),
                row.subscribedUsers(), row.contactsCount(), row.opportunitiesCount(), row.wikidataId(),
                row.latitude(), row.longitude(), row.blog(), row.linkedin(), row.facebook(), row.twitter(),
                row.bluesky(), row.instagram(), row.mastodon(), ts(row.deletedAt()), ts(row.createdAt()),
                ts(row.updatedAt()));
        return row.id();
    }

    public record AccountRow(
            long id, Long userId, Long assignedTo, String name, String access, String website, String tollFreePhone,
            String phone, String fax, String email, String backgroundInfo, int rating, String category,
            String subscribedUsers, int contactsCount, int opportunitiesCount, String wikidataId,
            java.math.BigDecimal latitude, java.math.BigDecimal longitude, String blog, String linkedin,
            String facebook, String twitter, String bluesky, String instagram, String mastodon,
            LocalDateTime deletedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {

        /** Minimal Public account owned by {@code userId}; every optional column {@code null}. */
        public static AccountRow minimal(long id, String name, Long userId) {
            LocalDateTime now = LocalDateTime.now().withNano(0);
            return new AccountRow(id, userId, null, name, "Public", null, null, null, null, null, null, 0, null,
                    null, 0, 0, null, null, null, null, null, null, null, null, null, null, null, now, now);
        }
    }

    // ---- contacts -------------------------------------------------------------------------

    public long insertContact(long id, String firstName, String lastName, Long userId, Long assignedTo, String access) {
        return insertContact(id, firstName, lastName, userId, assignedTo, access, null);
    }

    /** @param deletedAt non-null to seed a soft-deleted row */
    public long insertContact(long id, String firstName, String lastName, Long userId, Long assignedTo, String access,
            LocalDateTime deletedAt) {
        jdbc.update("""
                INSERT INTO contacts (id, first_name, last_name, user_id, assigned_to, access, do_not_call,
                                      deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, false, ?, now(), now())
                """, id, firstName, lastName, userId, assignedTo, access, ts(deletedAt));
        return id;
    }

    /** Full-column variant for tests that need to assert DTO field mapping. */
    public long insertContact(ContactRow row) {
        jdbc.update("""
                INSERT INTO contacts (id, user_id, lead_id, assigned_to, reports_to, first_name, last_name, access,
                                      title, department, source, email, alt_email, phone, mobile, fax, blog, linkedin,
                                      facebook, twitter, born_on, do_not_call, background_info, subscribed_users,
                                      zoom, teams, signal, instagram, mastodon, bluesky, deleted_at, created_at,
                                      updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id(), row.userId(), row.leadId(), row.assignedTo(), row.reportsTo(), row.firstName(),
                row.lastName(), row.access(), row.title(), row.department(), row.source(), row.email(),
                row.altEmail(), row.phone(), row.mobile(), row.fax(), row.blog(), row.linkedin(), row.facebook(),
                row.twitter(), row.bornOn(), row.doNotCall(), row.backgroundInfo(), row.subscribedUsers(),
                row.zoom(), row.teams(), row.signal(), row.instagram(), row.mastodon(), row.bluesky(),
                ts(row.deletedAt()), ts(row.createdAt()), ts(row.updatedAt()));
        return row.id();
    }

    public record ContactRow(
            long id, Long userId, Long leadId, Long assignedTo, Long reportsTo, String firstName, String lastName,
            String access, String title, String department, String source, String email, String altEmail,
            String phone, String mobile, String fax, String blog, String linkedin, String facebook, String twitter,
            LocalDate bornOn, boolean doNotCall, String backgroundInfo, String subscribedUsers, String zoom,
            String teams, String signal, String instagram, String mastodon, String bluesky,
            LocalDateTime deletedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {

        /** Minimal Public contact owned by {@code userId}; every optional column {@code null}. */
        public static ContactRow minimal(long id, String firstName, String lastName, Long userId) {
            LocalDateTime now = LocalDateTime.now().withNano(0);
            return new ContactRow(id, userId, null, null, null, firstName, lastName, "Public", null, null, null,
                    null, null, null, null, null, null, null, null, null, null, false, null, null, null, null, null,
                    null, null, null, null, now, now);
        }
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
