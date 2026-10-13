package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code EntitiesController#subscribe/#unsubscribe} run {@code entity.save}, so the
 * {@code subscribed_users} change only lands when the entity passes its own validations. The
 * AB-272 review fix moved the list mutation inside the saveable check and made unsubscribe render
 * the Rails 422 {@code {errors}} body; these tests pin that for every family (the PR's own test
 * covers a blank-name Account only), for the {@code users_for_shared_access} failure, and that
 * saveable entities still persist the change.
 */
class EntitySubscribeSaveabilityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    // ------------------------------------------------------------------ unsaveable: presence

    @Test
    void subscribeOnEntitiesFailingPresenceValidationPersistsNothingForEveryFamily()
        throws Exception {
        long campaignId = campaign("");
        long contactId = contact("", "");
        long leadId = lead("", "", "Public");
        long opportunityId = opportunity("");

        subscribe("campaigns", campaignId).andExpect(status().isInternalServerError());
        subscribe("contacts", contactId).andExpect(status().isInternalServerError());
        subscribe("leads", leadId).andExpect(status().isInternalServerError());
        subscribe("opportunities", opportunityId).andExpect(status().isInternalServerError());

        assertThat(subscribedUsers("campaigns", campaignId)).isNull();
        assertThat(subscribedUsers("contacts", contactId)).isNull();
        assertThat(subscribedUsers("leads", leadId)).isNull();
        assertThat(subscribedUsers("opportunities", opportunityId)).isNull();
    }

    @Test
    void unsubscribeOnEntitiesFailingPresenceValidationPersistsNothingForEveryFamily()
        throws Exception {
        long campaignId = campaign("");
        long contactId = contact("", "");
        long leadId = lead("", "", "Public");
        long opportunityId = opportunity("");
        String aliceOnly = yaml(alice.getId());
        for (String table : List.of("campaigns", "contacts", "leads", "opportunities")) {
            jdbcTemplate.update("UPDATE " + table + " SET subscribed_users = ?", aliceOnly);
        }

        // Rails: entity.save fails → respond_with renders 422 {errors} (unlike subscribe's 500).
        unsubscribe("campaigns", campaignId)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify campaign name."));
        unsubscribe("contacts", contactId)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.first_name[0]").value("^Please specify first name."))
            .andExpect(jsonPath("$.errors.last_name[0]").value("^Please specify last name."));
        unsubscribe("leads", leadId)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.first_name[0]").value("^Please specify first name."))
            .andExpect(jsonPath("$.errors.last_name[0]").value("^Please specify last name."));
        unsubscribe("opportunities", opportunityId)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify opportunity name."));

        assertThat(subscribedUsers("campaigns", campaignId)).isEqualTo(aliceOnly);
        assertThat(subscribedUsers("contacts", contactId)).isEqualTo(aliceOnly);
        assertThat(subscribedUsers("leads", leadId)).isEqualTo(aliceOnly);
        assertThat(subscribedUsers("opportunities", opportunityId)).isEqualTo(aliceOnly);
    }

    // ------------------------------------------------------------------ unsaveable: shared access

    /** {@code validate :users_for_shared_access}: Shared with no permission rows fails the save. */
    @Test
    void subscribeOnSharedEntityWithoutPermissionsPersistsNothing() throws Exception {
        long accountId = account("Shared Acme", "Shared");
        long leadId = lead("Sh", "Ared", "Shared");

        subscribe("accounts", accountId).andExpect(status().isInternalServerError());
        subscribe("leads", leadId).andExpect(status().isInternalServerError());

        assertThat(subscribedUsers("accounts", accountId)).isNull();
        assertThat(subscribedUsers("leads", leadId)).isNull();
    }

    @Test
    void unsubscribeOnSharedEntityWithoutPermissionsPersistsNothing() throws Exception {
        long accountId = account("Shared Acme", "Shared");
        String both = yaml(alice.getId(), bob.getId());
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?", both, accountId);

        unsubscribe("accounts", accountId)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.access[0]")
                .value("^Please specify users with whom to share the account."));

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(both);
    }

    @Test
    void sharedEntityWithAPermissionRowIsSaveableSoSubscribePersists() throws Exception {
        long accountId = account("Shared Acme", "Shared");
        jdbcTemplate.update("INSERT INTO permissions (user_id, asset_type, asset_id, created_at,"
            + " updated_at) VALUES (?, 'Account', ?, now(), now())", bob.getId(), accountId);

        subscribe("accounts", accountId).andExpect(status().isInternalServerError());

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(yaml(alice.getId()));
    }

    // ------------------------------------------------------------------ saveable: the change lands

    @Test
    void subscribeOnSaveableEntitiesAppendsTheCallerForEveryFamily() throws Exception {
        long accountId = account("Acme", "Public");
        long campaignId = campaign("Launch");
        long contactId = contact("Ann", "Lee");
        long leadId = lead("Bo", "Ray", "Public");
        long opportunityId = opportunity("Deal");
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?",
            yaml(bob.getId()), accountId);

        subscribe("accounts", accountId).andExpect(status().isInternalServerError());
        subscribe("campaigns", campaignId).andExpect(status().isInternalServerError());
        subscribe("contacts", contactId).andExpect(status().isInternalServerError());
        subscribe("leads", leadId).andExpect(status().isInternalServerError());
        subscribe("opportunities", opportunityId).andExpect(status().isInternalServerError());

        assertThat(subscribedUsers("accounts", accountId))
            .as("appended after the existing subscriber, like subscribed_users += [id]")
            .isEqualTo(yaml(bob.getId(), alice.getId()));
        assertThat(subscribedUsers("campaigns", campaignId)).isEqualTo(yaml(alice.getId()));
        assertThat(subscribedUsers("contacts", contactId)).isEqualTo(yaml(alice.getId()));
        assertThat(subscribedUsers("leads", leadId)).isEqualTo(yaml(alice.getId()));
        assertThat(subscribedUsers("opportunities", opportunityId)).isEqualTo(yaml(alice.getId()));
    }

    @Test
    void unsubscribeOnSaveableEntitiesRemovesTheCallerAndReturnsTheEntity() throws Exception {
        long accountId = account("Acme", "Public");
        long contactId = contact("Ann", "Lee");
        String both = yaml(alice.getId(), bob.getId());
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?", both, accountId);
        jdbcTemplate.update("UPDATE contacts SET subscribed_users = ? WHERE id = ?", both, contactId);

        unsubscribe("accounts", accountId)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value((int) accountId))
            .andExpect(jsonPath("$.name").value("Acme"));
        unsubscribe("contacts", contactId)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value((int) contactId));

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(yaml(bob.getId()));
        assertThat(subscribedUsers("contacts", contactId)).isEqualTo(yaml(bob.getId()));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions subscribe(String family, long id)
        throws Exception {
        return mockMvc.perform(post("/api/v1/" + family + "/" + id + "/subscribe")
            .header(HttpHeaders.AUTHORIZATION, aliceBearer));
    }

    private org.springframework.test.web.servlet.ResultActions unsubscribe(String family, long id)
        throws Exception {
        return mockMvc.perform(post("/api/v1/" + family + "/" + id + "/unsubscribe")
            .header(HttpHeaders.AUTHORIZATION, aliceBearer));
    }

    private static String yaml(Long... userIds) {
        StringBuilder yaml = new StringBuilder("---\n");
        for (Long userId : userIds) {
            yaml.append("- ").append(userId).append('\n');
        }
        return yaml.toString();
    }

    private String subscribedUsers(String table, long id) {
        return jdbcTemplate.queryForObject(
            "SELECT subscribed_users FROM " + table + " WHERE id = ?", String.class, id);
    }

    private long account(String name, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, assigned_to, access, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, now(), now()) RETURNING id",
            Long.class, name, alice.getId(), alice.getId(), access);
    }

    private long campaign(String name) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO campaigns (name, user_id, assigned_to, access, leads_count,"
                + " opportunities_count, created_at, updated_at)"
                + " VALUES (?, ?, ?, 'Public', 0, 0, now(), now()) RETURNING id",
            Long.class, name, alice.getId(), alice.getId());
    }

    private long contact(String firstName, String lastName) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name, user_id, assigned_to, access, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, 'Public', now(), now()) RETURNING id",
            Long.class, firstName, lastName, alice.getId(), alice.getId());
    }

    private long lead(String firstName, String lastName, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO leads (first_name, last_name, user_id, assigned_to, access, status,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'new', now(), now()) RETURNING id",
            Long.class, firstName, lastName, alice.getId(), alice.getId(), access);
    }

    private long opportunity(String name) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO opportunities (name, user_id, assigned_to, access, stage, created_at,"
                + " updated_at) VALUES (?, ?, ?, 'Public', 'prospecting', now(), now()) RETURNING id",
            Long.class, name, alice.getId(), alice.getId());
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void clearData() {
        for (String table : List.of("versions", "permissions", "contacts", "leads", "opportunities",
            "campaigns", "accounts", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
