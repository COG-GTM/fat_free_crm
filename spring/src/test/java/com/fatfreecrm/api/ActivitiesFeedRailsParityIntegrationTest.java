package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.User;
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

/**
 * Pins {@code GET /api/v1/activities} to the Rails {@code HomeController#activity_*} helpers and
 * {@code Version.visible_to}: multi-word actor lookup follows {@code String#name_permutations},
 * unknown actors and {@code all_users} disable the actor filter, {@code activity_asset} is
 * singularised with Rails inflection ({@code opportunities} -> {@code Opportunity}), and models
 * without an {@code access} column (Task, Comment) are visible to everyone while deleted rows
 * that cannot be reified are dropped.
 */
class ActivitiesFeedRailsParityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User viewer;
    private User bob;
    private String viewerBearer;

    @BeforeEach
    void seed() {
        clearData();
        viewer = user("viewer", "Viewer", "Person");
        bob = user("bob", "Bob", "Smith");
        viewerBearer = bearer(viewer);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void multiWordActorFollowsRailsNamePermutationOrder() throws Exception {
        User maryAnnLee = user("mary1", "Mary", "Ann Lee");
        User maryAnn = user("mary2", "Mary Ann", "Lee");
        Instant now = Instant.now();
        insertVersion("Task", 1, "create", maryAnnLee.getId(), now, null);
        insertVersion("Task", 2, "create", maryAnn.getId(), now, null);
        insertVersion("Task", 3, "create", bob.getId(), now, null);
        for (int id = 1; id <= 3; id++) {
            insertTask(id, bob);
        }

        assertThat(itemIds(feed(new String[][] {{"user", "Mary Ann Lee"}, {"asset", "tasks"}})))
            .as("first matching permutation [Mary, Ann Lee] wins, as in Rails name_permutations")
            .containsExactly(1);
        assertThat(itemIds(feed(new String[][] {{"user", "Lee Mary Ann"}, {"asset", "tasks"}})))
            .as("reversed permutation [Mary Ann, Lee] is also searched")
            .containsExactly(2);
        assertThat(itemIds(feed(new String[][] {{"user", "  Bob   Smith "}, {"asset", "tasks"}})))
            .as("surrounding and repeated whitespace is tolerated like String#split")
            .containsExactly(3);
    }

    @Test
    void singleWordActorPrefersFirstNameThenLastNameAndEmailMatchesWholeAddress() throws Exception {
        User smithFirst = user("smith", "Smith", "Jones");
        Instant now = Instant.now();
        insertVersion("Task", 1, "create", bob.getId(), now, null);
        insertVersion("Task", 2, "create", smithFirst.getId(), now, null);
        insertTask(1, bob);
        insertTask(2, bob);

        assertThat(itemIds(feed(new String[][] {{"user", "Smith"}, {"asset", "tasks"}})))
            .as("first_name match is preferred over Bob's last_name match")
            .containsExactly(2);
        assertThat(itemIds(feed(new String[][] {{"user", "Jones"}, {"asset", "tasks"}})))
            .as("falls back to last_name when no first_name matches")
            .containsExactly(2);
        assertThat(itemIds(feed(new String[][] {{"user", "bob@example.test"}, {"asset", "tasks"}})))
            .containsExactly(1);
        assertThat(itemIds(feed(new String[][] {{"user", "BOB@example.test"}, {"asset", "tasks"}})))
            .as("email lookup is exact like User.where(email:); an unmatched email yields nil and no filter")
            .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void unknownActorAndAllUsersDisableTheActorFilter() throws Exception {
        Instant now = Instant.now();
        insertVersion("Task", 1, "create", bob.getId(), now, null);
        insertVersion("Task", 2, "create", viewer.getId(), now, null);
        insertTask(1, bob);
        insertTask(2, viewer);

        assertThat(itemIds(feed(new String[][] {{"user", "Nobody Here"}, {"asset", "tasks"}})))
            .as("Rails name_query returns nil for an unknown person, so no whodunnit filter applies")
            .containsExactlyInAnyOrder(1, 2);
        assertThat(itemIds(feed(new String[][] {{"user", "all_users"}, {"asset", "tasks"}})))
            .containsExactlyInAnyOrder(1, 2);
        assertThat(itemIds(feed(new String[][] {{"user", "Bob Smith"}, {"asset", "tasks"}})))
            .containsExactly(1);
    }

    @Test
    void assetParameterIsSingularizedWithRailsInflection() throws Exception {
        Instant now = Instant.now();
        insertVersion("Opportunity", 7, "destroy", bob.getId(), now, reified("Opportunity", 7, "Public"));
        insertVersion("Account", 8, "destroy", bob.getId(), now, reified("Account", 8, "Public"));
        insertTask(9, bob);
        insertVersion("Task", 9, "create", bob.getId(), now, null);

        assertThat(itemTypes(feed(new String[][] {{"asset", "opportunities"}, {"event", "all_events"}})))
            .containsExactly("Opportunity");
        assertThat(itemTypes(feed(new String[][] {{"asset", "accounts"}, {"event", "all_events"}})))
            .containsExactly("Account");
        assertThat(itemTypes(feed(new String[][] {{"asset", "tasks"}, {"event", "all_events"}})))
            .containsExactly("Task");
        assertThat(itemTypes(feed(new String[][] {{"asset", "all"}, {"event", "all_events"}})))
            .containsExactlyInAnyOrder("Opportunity", "Account", "Task");
    }

    @Test
    void tasksAndCommentsHaveNoAccessControlButUnreifiableDeletedRowsAreDropped() throws Exception {
        Instant now = Instant.now();
        insertTask(1, bob);
        insertVersion("Task", 1, "create", bob.getId(), now, null);
        insertVersion("Task", 2, "destroy", bob.getId(), now, null);
        insertVersion("Task", 3, "destroy", bob.getId(), now,
            "--- !ruby/object:Task\nid: 3\nuser_id: " + bob.getId() + "\nname: Gone\n");
        jdbcTemplate.update(
            "INSERT INTO comments (id, user_id, commentable_type, commentable_id, private, title, comment, "
                + "created_at, updated_at, state) "
                + "VALUES (4, ?, 'Account', 99, TRUE, '', 'private note', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, "
                + "'Expanded')",
            bob.getId());
        insertVersion("Comment", 4, "create", bob.getId(), now, null);
        insertVersion("Comment", 5, "destroy", bob.getId(), now, null);

        JsonNode feed = feed(new String[][] {{"asset", "all"}, {"event", "all_events"}});

        List<String> keys = new ArrayList<>();
        for (JsonNode version : feed) {
            keys.add(version.path("item_type").asText() + ":" + version.path("item_id").asInt());
        }
        assertThat(keys)
            .as("live rows and reified deletes are visible regardless of owner; destroys with no object vanish")
            .containsExactlyInAnyOrder("Task:1", "Task:3", "Comment:4");
    }

    @Test
    void multiLineSecretBlocksAreScrubbedFromObjectAndObjectChanges() throws Exception {
        Instant now = Instant.now();
        insertTask(1, bob);
        String object = "--- !ruby/object:Task\n"
            + "id: 1\n"
            + "name: Call\n"
            + "encrypted_password: |-\n"
            + "  line one\n"
            + "  line two\n"
            + "authentication_token: abc\n"
            + "password_salt:\n"
            + "- first\n"
            + "- second\n"
            + "background_info: keep me\n";
        jdbcTemplate.update(
            "INSERT INTO versions (item_type, item_id, event, whodunnit, object, object_changes, created_at) "
                + "VALUES ('Task', 1, 'update', ?, ?, ?, ?)",
            bob.getId().toString(), object,
            "---\nencrypted_password:\n- old\n- new\nname:\n- Old\n- Call\n", Timestamp.from(now));

        JsonNode version = feed(new String[][] {{"asset", "tasks"}, {"event", "update"}}).get(0);

        assertThat(version.path("object").asText())
            .isEqualTo("--- !ruby/object:Task\nid: 1\nname: Call\nbackground_info: keep me\n");
        assertThat(version.path("object_changes").asText()).isEqualTo("---\nname:\n- Old\n- Call\n");
    }

    private JsonNode feed(String[][] parameters) throws Exception {
        var request = get("/api/v1/activities").header(HttpHeaders.AUTHORIZATION, viewerBearer);
        for (String[] parameter : parameters) {
            request = request.param(parameter[0], parameter[1]);
        }
        return JSON.readTree(mockMvc.perform(request).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private static List<Integer> itemIds(JsonNode feed) {
        List<Integer> ids = new ArrayList<>();
        feed.forEach(version -> ids.add(version.path("item_id").asInt()));
        return ids;
    }

    private static List<String> itemTypes(JsonNode feed) {
        List<String> types = new ArrayList<>();
        feed.forEach(version -> types.add(version.path("item_type").asText()));
        return types;
    }

    private static String reified(String type, int id, String access) {
        return "--- !ruby/object:" + type + "\nid: " + id + "\nuser_id: 999999\nassigned_to:\naccess: " + access + "\n";
    }

    private User user(String username, String firstName, String lastName) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void insertTask(int id, User owner) {
        jdbcTemplate.update(
            "INSERT INTO tasks (id, user_id, name, created_at, updated_at) "
                + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            id, owner.getId(), "Task " + id);
    }

    private void insertVersion(String type, int itemId, String event, Long actor, Instant createdAt, String object) {
        jdbcTemplate.update(
            "INSERT INTO versions (item_type, item_id, event, whodunnit, object, object_changes, created_at) "
                + "VALUES (?, ?, ?, ?, ?, NULL, ?)",
            type, itemId, event, actor == null ? null : actor.toString(), object, Timestamp.from(createdAt));
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
