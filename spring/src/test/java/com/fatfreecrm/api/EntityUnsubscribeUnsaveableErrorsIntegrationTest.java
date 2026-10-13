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
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code EntitiesController#unsubscribe} runs {@code entity.save} and hands the entity to
 * {@code respond_with}: when the save fails validation Rails renders the 422
 * {@code {"errors": {attr: [msg]}}} body built from {@code errors.as_json} and the row is left
 * untouched. The PR pins this for a blank-name Account only; these tests pin the same error
 * semantics for every family and every validation {@code entitySaveable} can fail
 * ({@code validates_presence_of} and {@code users_for_shared_access}), that authorization still
 * runs ahead of validation, and that the 422 path writes nothing (row, version).
 */
class EntityUnsubscribeUnsaveableErrorsIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        bobBearer = "Bearer " + jwtTokenService.issue(bob).accessToken();
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    // ------------------------------------------------------------------ presence failures per family

    @Test
    void campaignWithBlankNameReturnsRailsErrorsBodyAndPersistsNothing() throws Exception {
        long campaignId = campaign("");
        String aliceOnly = yaml(alice.getId());
        setSubscribed("campaigns", campaignId, aliceOnly);

        unsubscribe("campaigns", campaignId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors.name.length()").value(1))
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify campaign name."))
            .andExpect(jsonPath("$.id").doesNotExist())
            .andExpect(jsonPath("$.subscribed_users").doesNotExist());

        assertThat(subscribedUsers("campaigns", campaignId)).isEqualTo(aliceOnly);
    }

    @Test
    void contactWithBlankNamesReportsBothPresenceErrorsInValidatorOrder() throws Exception {
        long contactId = contact("", "");
        String aliceOnly = yaml(alice.getId());
        setSubscribed("contacts", contactId, aliceOnly);

        String body = unsubscribe("contacts", contactId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.length()").value(2))
            .andExpect(jsonPath("$.errors.first_name[0]").value("^Please specify first name."))
            .andExpect(jsonPath("$.errors.last_name[0]").value("^Please specify last name."))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).containsSubsequence("\"first_name\"", "\"last_name\"");

        assertThat(subscribedUsers("contacts", contactId)).isEqualTo(aliceOnly);
    }

    @Test
    void contactWithOnlyLastNameBlankReportsJustThatAttribute() throws Exception {
        long contactId = contact("Ann", "");
        setSubscribed("contacts", contactId, yaml(alice.getId()));

        unsubscribe("contacts", contactId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors.last_name[0]").value("^Please specify last name."));
    }

    @Test
    void leadWithBlankNamesReturnsRailsErrorsBody() throws Exception {
        long leadId = lead("", "", "Public");
        String aliceOnly = yaml(alice.getId());
        setSubscribed("leads", leadId, aliceOnly);

        unsubscribe("leads", leadId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.length()").value(2))
            .andExpect(jsonPath("$.errors.first_name[0]").value("^Please specify first name."))
            .andExpect(jsonPath("$.errors.last_name[0]").value("^Please specify last name."));

        assertThat(subscribedUsers("leads", leadId)).isEqualTo(aliceOnly);
    }

    @Test
    void opportunityWithBlankNameReturnsRailsErrorsBody() throws Exception {
        long opportunityId = opportunity("");
        String aliceOnly = yaml(alice.getId());
        setSubscribed("opportunities", opportunityId, aliceOnly);

        unsubscribe("opportunities", opportunityId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify opportunity name."));

        assertThat(subscribedUsers("opportunities", opportunityId)).isEqualTo(aliceOnly);
    }

    /** Whitespace-only is {@code blank?} in Rails, so presence fails exactly like the empty string. */
    @Test
    void whitespaceOnlyNameIsBlankForPresence() throws Exception {
        long accountId = account("   ", "Public");
        setSubscribed("accounts", accountId, yaml(alice.getId()));

        unsubscribe("accounts", accountId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify account name."));

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(yaml(alice.getId()));
    }

    // ------------------------------------------------------------------ users_for_shared_access

    @Test
    void sharedEntityWithoutPermissionRowsReportsShareErrorOnAccessForEveryFamily()
        throws Exception {
        long accountId = account("Acme", "Shared");
        long campaignId = campaign("Launch", "Shared");
        long contactId = contact("Ann", "Lee", "Shared");
        long leadId = lead("Bo", "Ray", "Shared");
        long opportunityId = opportunity("Deal", "Shared");
        String both = yaml(alice.getId(), bob.getId());
        for (String table : List.of("accounts", "campaigns", "contacts", "leads", "opportunities")) {
            jdbcTemplate.update("UPDATE " + table + " SET subscribed_users = ?", both);
        }

        expectShareError(unsubscribe("accounts", accountId, aliceBearer), "account");
        expectShareError(unsubscribe("campaigns", campaignId, aliceBearer), "campaign");
        expectShareError(unsubscribe("contacts", contactId, aliceBearer), "contact");
        expectShareError(unsubscribe("leads", leadId, aliceBearer), "lead");
        expectShareError(unsubscribe("opportunities", opportunityId, aliceBearer), "opportunity");

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(both);
        assertThat(subscribedUsers("campaigns", campaignId)).isEqualTo(both);
        assertThat(subscribedUsers("contacts", contactId)).isEqualTo(both);
        assertThat(subscribedUsers("leads", leadId)).isEqualTo(both);
        assertThat(subscribedUsers("opportunities", opportunityId)).isEqualTo(both);
    }

    /** Presence and shared-access both fail: {@code errors.as_json} lists them in validator order. */
    @Test
    void blankNameAndMissingShareUsersAreBothReported() throws Exception {
        long accountId = account("", "Shared");
        setSubscribed("accounts", accountId, yaml(alice.getId()));

        String body = unsubscribe("accounts", accountId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.length()").value(2))
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify account name."))
            .andExpect(jsonPath("$.errors.access[0]")
                .value("^Please specify users with whom to share the account."))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).containsSubsequence("\"name\"", "\"access\"");
    }

    /** A single permission row makes the Shared entity saveable again, so the unsubscribe lands. */
    @Test
    void sharedEntityWithAPermissionRowUnsubscribesNormally() throws Exception {
        long leadId = lead("Bo", "Ray", "Shared");
        jdbcTemplate.update("INSERT INTO permissions (user_id, asset_type, asset_id, created_at,"
            + " updated_at) VALUES (?, 'Lead', ?, now(), now())", bob.getId(), leadId);
        setSubscribed("leads", leadId, yaml(alice.getId(), bob.getId()));

        unsubscribe("leads", leadId, aliceBearer)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value((int) leadId))
            .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(subscribedUsers("leads", leadId)).isEqualTo(yaml(bob.getId()));
    }

    // ------------------------------------------------------------------ edge: nothing to remove

    /** Rails still calls {@code save}, so an unsubscribed caller on an invalid row gets the 422 too. */
    @Test
    void callerNotSubscribedStillGetsValidationErrorsOnUnsaveableEntity() throws Exception {
        long accountId = account("", "Public");
        String bobOnly = yaml(bob.getId());
        setSubscribed("accounts", accountId, bobOnly);

        unsubscribe("accounts", accountId, aliceBearer)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify account name."));

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(bobOnly);
    }

    @Test
    void unsaveableEntityWithNoSubscribersStaysNull() throws Exception {
        long opportunityId = opportunity("");

        unsubscribe("opportunities", opportunityId, aliceBearer)
            .andExpect(status().isUnprocessableEntity());

        assertThat(subscribedUsers("opportunities", opportunityId)).isNull();
    }

    // ------------------------------------------------------------------ data integrity

    @Test
    void failedUnsubscribeWritesNoVersionAndDoesNotTouchUpdatedAt() throws Exception {
        long accountId = account("", "Public");
        jdbcTemplate.update(
            "UPDATE accounts SET updated_at = '2024-01-01 00:00:00' WHERE id = ?", accountId);
        setSubscribed("accounts", accountId, yaml(alice.getId()));
        Integer versionsBefore = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM versions", Integer.class);

        unsubscribe("accounts", accountId, aliceBearer).andExpect(status().isUnprocessableEntity());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM versions", Integer.class))
            .isEqualTo(versionsBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT to_char(updated_at, 'YYYY-MM-DD') FROM accounts WHERE id = ?",
            String.class, accountId)).isEqualTo("2024-01-01");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE id = ?", String.class, accountId)).isEmpty();
    }

    // ------------------------------------------------------------------ authorization runs first

    @Test
    void anonymousCallerGets401NotValidationErrors() throws Exception {
        long accountId = account("", "Public");
        setSubscribed("accounts", accountId, yaml(alice.getId()));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/unsubscribe"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(yaml(alice.getId()));
    }

    /** Another user's Private row is out of scope: {@code hasPermission} denies before validation. */
    @Test
    void crossUserPrivateUnsaveableEntityIsDeniedBeforeValidation() throws Exception {
        long accountId = account("", "Private");
        String both = yaml(alice.getId(), bob.getId());
        setSubscribed("accounts", accountId, both);

        unsubscribe("accounts", accountId, bobBearer)
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errors").doesNotExist());

        assertThat(subscribedUsers("accounts", accountId)).isEqualTo(both);
    }

    @Test
    void unknownIdIs404NotValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/leads/999999/unsubscribe")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errors").doesNotExist());
    }

    // ------------------------------------------------------------------ helpers

    private void expectShareError(ResultActions result, String model) throws Exception {
        result.andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.length()").value(1))
            .andExpect(jsonPath("$.errors.access.length()").value(1))
            .andExpect(jsonPath("$.errors.access[0]")
                .value("^Please specify users with whom to share the " + model + "."));
    }

    private ResultActions unsubscribe(String family, long id, String bearer) throws Exception {
        return mockMvc.perform(post("/api/v1/" + family + "/" + id + "/unsubscribe")
            .header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private static String yaml(Long... userIds) {
        StringBuilder yaml = new StringBuilder("---\n");
        for (Long userId : userIds) {
            yaml.append("- ").append(userId).append('\n');
        }
        return yaml.toString();
    }

    private void setSubscribed(String table, long id, String yaml) {
        jdbcTemplate.update("UPDATE " + table + " SET subscribed_users = ? WHERE id = ?", yaml, id);
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
        return campaign(name, "Public");
    }

    private long campaign(String name, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO campaigns (name, user_id, assigned_to, access, leads_count,"
                + " opportunities_count, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 0, 0, now(), now()) RETURNING id",
            Long.class, name, alice.getId(), alice.getId(), access);
    }

    private long contact(String firstName, String lastName) {
        return contact(firstName, lastName, "Public");
    }

    private long contact(String firstName, String lastName, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO contacts (first_name, last_name, user_id, assigned_to, access, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, ?, now(), now()) RETURNING id",
            Long.class, firstName, lastName, alice.getId(), alice.getId(), access);
    }

    private long lead(String firstName, String lastName, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO leads (first_name, last_name, user_id, assigned_to, access, status,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'new', now(), now()) RETURNING id",
            Long.class, firstName, lastName, alice.getId(), alice.getId(), access);
    }

    private long opportunity(String name) {
        return opportunity(name, "Public");
    }

    private long opportunity(String name, String access) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO opportunities (name, user_id, assigned_to, access, stage, created_at,"
                + " updated_at) VALUES (?, ?, ?, ?, 'prospecting', now(), now()) RETURNING id",
            Long.class, name, alice.getId(), alice.getId(), access);
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
