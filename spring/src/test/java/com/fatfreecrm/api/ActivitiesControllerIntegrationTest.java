package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsBase64;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

class ActivitiesControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private User admin;
    private String aliceBearer;
    private String adminBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", "Alice", false);
        bob = user("bob", "Bob", false);
        admin = user("admin", "Admin", true);
        aliceBearer = bearer(alice);
        adminBearer = bearer(admin);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void activityFiltersUsePreferencesAndExplicitParametersOverrideThem() throws Exception {
        insertVersion("User", alice.getId().intValue(), "update", alice.getId(), Instant.now(),
            "---\nusername: Alice\nencrypted_password: encrypted\npassword_salt: salt\nsafe: kept\n");
        insertVersion("User", alice.getId().intValue(), "view", alice.getId(), Instant.now(), "---\nusername: Alice\n");
        insertVersion("User", bob.getId().intValue(), "create", bob.getId(), Instant.now(), "---\nusername: Bob\n");
        insertVersion("Account", 777, "update", alice.getId(), Instant.now(), "---\nname: Other asset\n");

        preference("activity_asset", "\"users\"");
        preference("activity_event", "\"update\"");
        preference("activity_user", "\"alice@example.test\"");
        preference("activity_duration", "\"one_day\"");

        JsonNode defaultFeed = feed(aliceBearer, null);
        assertThat(defaultFeed.isArray()).isTrue();
        assertThat(defaultFeed).hasSize(1);
        assertThat(defaultFeed.get(0).path("item_type").asText()).isEqualTo("User");
        assertThat(defaultFeed.get(0).path("object").asText()).contains("safe: kept")
            .doesNotContainIgnoringCase("password", "salt", "token");
        assertThat(defaultFeed.get(0).path("object_changes").asText())
            .doesNotContainIgnoringCase("password", "salt", "token");

        JsonNode overridden = feed(aliceBearer, new String[][] {
            {"asset", "users"}, {"event", "all_events"}, {"user", "bob@example.test"}, {"duration", "two_days"}
        });
        assertThat(overridden).hasSize(1);
        assertThat(overridden.get(0).path("event").asText()).isEqualTo("create");
        JsonNode nameActor = feed(aliceBearer, new String[][] {
            {"asset", "users"}, {"event", "all_events"}, {"user", "Bob"}, {"duration", "two_days"}
        });
        assertThat(nameActor).hasSize(1);
        assertThat(nameActor.get(0).path("item_id").asLong()).isEqualTo(bob.getId());
        JsonNode ordered = feed(aliceBearer, new String[][] {
            {"asset", "users"}, {"event", "all_events"}, {"user", "all_users"}, {"duration", "two_days"}
        });
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).path("item_id").asLong()).isEqualTo(bob.getId());
        assertThat(ordered.get(1).path("item_id").asLong()).isEqualTo(alice.getId());
        assertThat(ordered.toString()).doesNotContain("\"view\"");

        JsonNode eventFilter = feed(aliceBearer, new String[][] {{"asset", "users"}, {"event", "view"}});
        assertThat(eventFilter).hasSize(1);
        assertThat(eventFilter.get(0).path("event").asText()).isEqualTo("view");
        JsonNode assetFilter = feed(aliceBearer, new String[][] {{"asset", "accounts"}});
        assertThat(assetFilter).hasSize(1);
        assertThat(assetFilter.get(0).path("item_type").asText()).isEqualTo("Account");
        mockMvc.perform(get("/api/v1/activities")).andExpect(status().isUnauthorized());
    }

    @Test
    void actorWithoutWhitespacePrefersFirstNameMatchesBeforeLastNameMatches() throws Exception {
        bob.setLastName("SharedMatch");
        userRepository.saveAndFlush(bob);
        admin.setFirstName("SharedMatch");
        userRepository.saveAndFlush(admin);
        insertVersion("User", bob.getId().intValue(), "update", bob.getId(), Instant.now(), "---\nusername: Bob\n");
        insertVersion("User", admin.getId().intValue(), "update", admin.getId(), Instant.now(),
            "---\nusername: Admin\n");

        JsonNode matches = feed(aliceBearer, new String[][] {
            {"event", "all_events"}, {"user", "SharedMatch"}
        });

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).path("item_id").asLong()).isEqualTo(admin.getId());
    }

    @Test
    void durationOverrideAndRailsVisibilityRulesCoverReifiedItemsAndDirectPermissions() throws Exception {
        Instant now = Instant.now();
        insertVersion("User", alice.getId().intValue(), "update", alice.getId(), now.minusSeconds(5_400),
            "---\nusername: Alice\n");
        insertVersion("User", alice.getId().intValue(), "update", alice.getId(), now.minusSeconds(9 * 86_400L),
            "---\nusername: Alice\n");
        insertVersion("User", alice.getId().intValue(), "update", alice.getId(), now.minusSeconds(40 * 86_400L),
            "---\nusername: Alice\n");
        preference("activity_duration", "\"one_hour\"");
        assertThat(feed(aliceBearer, new String[][] {{"event", "all_events"}})).isEmpty();
        assertThat(feed(aliceBearer, new String[][] {{"duration", "two_hour"}, {"event", "all_events"}})).hasSize(1);
        assertThat(feed(aliceBearer, new String[][] {{"duration", "two_days"}, {"event", "all_events"}})).hasSize(1);
        assertThat(feed(aliceBearer, new String[][] {{"duration", "one_week"}, {"event", "all_events"}})).hasSize(1);
        assertThat(feed(aliceBearer, new String[][] {{"duration", "two_weeks"}, {"event", "all_events"}})).hasSize(2);
        assertThat(feed(aliceBearer, new String[][] {{"duration", "one_month"}, {"event", "all_events"}})).hasSize(2);
        assertThat(feed(aliceBearer, new String[][] {{"duration", "two_month"}, {"event", "all_events"}})).hasSize(3);
        assertThat(feed(aliceBearer, new String[][] {{"duration", "invalid"}, {"event", "all_events"}})).hasSize(1);

        insertAccount(801, bob, null, "Private");
        insertAccount(802, bob, alice.getId(), "Private");
        insertAccount(803, bob, null, "Shared");
        insertAccount(804, bob, null, "Shared");
        jdbcTemplate.update("INSERT INTO groups (id, name) VALUES (10, 'Shared group')");
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (10, ?)", alice.getId());
        jdbcTemplate.update(
            "INSERT INTO permissions (asset_type, asset_id, group_id) VALUES ('Account', 803, 10)");
        jdbcTemplate.update(
            "INSERT INTO permissions (asset_type, asset_id, user_id) VALUES ('Account', 804, ?)", alice.getId());

        insertVersion(
            "Account", 801, "update", bob.getId(), now,
            "---\naccess: Private\nuser_id: " + bob.getId() + "\n");
        insertVersion(
            "Account", 802, "update", bob.getId(), now,
            "---\naccess: Private\nuser_id: " + bob.getId() + "\n");
        insertVersion(
            "Account", 803, "update", bob.getId(), now,
            "---\naccess: Shared\nuser_id: " + bob.getId() + "\n");
        insertVersion(
            "Account", 804, "update", bob.getId(), now,
            "---\naccess: Shared\nuser_id: " + bob.getId() + "\n");
        insertVersion("Account", 899, "update", bob.getId(), now,
            "---\nuser_id: " + bob.getId() + "\nassigned_to: " + alice.getId() + "\naccess: Private\n");
        insertVersion("Account", 900, "update", bob.getId(), now,
            "---\nuser_id: " + bob.getId() + "\nassigned_to: \naccess: Private\n");
        insertVersion("Account", 901, "update", bob.getId(), now, null);
        insertVersion("UnknownModel", 902, "update", bob.getId(), now, "---\naccess: Public\n");

        JsonNode visible = feed(aliceBearer, new String[][] {{"event", "all_events"}});
        List<Integer> itemIds = new ArrayList<>();
        visible.forEach(version -> itemIds.add(version.path("item_id").asInt()));
        assertThat(itemIds).containsExactlyInAnyOrder(802, 804, 899);
        JsonNode adminVisible = feed(adminBearer, new String[][] {{"asset", "accounts"}, {"event", "all_events"}});
        assertThat(adminVisible).isEmpty();
    }

    @Test
    void deletedAccountWithTimeWithZoneYamlRemainsVisibleWhenPublic() throws Exception {
        Instant now = Instant.now();
        String privateObject = "--- !ruby/object:Account\n"
            + "id: 990\n"
            + "user_id: " + bob.getId() + "\n"
            + "assigned_to:\n"
            + "access: Private\n"
            + "created_at: !ruby/object:ActiveSupport::TimeWithZone\n"
            + "  utc: 2025-01-01 00:00:00.000000000 Z\n"
            + "  zone: !ruby/object:ActiveSupport::TimeZone\n"
            + "    name: UTC\n"
            + "  time: 2025-01-01 00:00:00.000000000 Z\n";
        String publicObject = privateObject.replace("id: 990", "id: 991").replace("access: Private", "access: Public");
        insertVersion("Account", 990, "destroy", bob.getId(), now, privateObject);
        insertVersion("Account", 991, "destroy", bob.getId(), now, publicObject);

        JsonNode visible = feed(aliceBearer, new String[][] {{"event", "all_events"}, {"asset", "accounts"}});
        List<Integer> itemIds = new ArrayList<>();
        visible.forEach(version -> itemIds.add(version.path("item_id").asInt()));

        assertThat(itemIds).contains(991).doesNotContain(990);
    }

    @Test
    void appliesFiveHundredRowLimitBeforeVisibilityAndDropsUnknownTypes() throws Exception {
        Instant now = Instant.now();
        insertVersion("User", alice.getId().intValue(), "update", alice.getId(), now.minusSeconds(7_200),
            "---\nusername: Alice\n");
        for (int index = 0; index < 500; index++) {
            insertVersion("UnknownModel", 100_000 + index, "update", bob.getId(), now, "---\nname: ignored\n");
        }
        assertThat(feed(aliceBearer, new String[][] {{"event", "all_events"}})).isEmpty();
    }

    private JsonNode feed(String bearer, String[][] parameters) throws Exception {
        var request = get("/api/v1/activities").header(HttpHeaders.AUTHORIZATION, bearer);
        if (parameters != null) {
            for (String[] parameter : parameters) {
                request = request.param(parameter[0], parameter[1]);
            }
        }
        return JSON.readTree(mockMvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private User user(String username, String firstName, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFirstName(firstName);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(isAdmin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void insertAccount(int id, User owner, Long assignedTo, String access) {
        jdbcTemplate.update(
            "INSERT INTO accounts (id, name, access, user_id, assigned_to, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            id, "Account " + id, access, owner.getId(), assignedTo
        );
    }

    private void insertVersion(String type, int itemId, String event, Long actor, Instant createdAt, String object) {
        jdbcTemplate.update(
            "INSERT INTO versions (item_type, item_id, event, whodunnit, object, object_changes, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
            type, itemId, event, actor == null ? null : actor.toString(), object, object, Timestamp.from(createdAt)
        );
    }

    private void preference(String name, String json) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            "INSERT INTO preferences (user_id, name, value, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            alice.getId(), name, RailsBase64.encode64(json), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM groups_users");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM groups");
        jdbcTemplate.update("DELETE FROM users");
    }
}
