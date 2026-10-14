package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthlogicSha512PasswordEncoder;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.service.audit.EntityAttributes;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code /api/v1/admin/users} writes pinned to Rails {@code Admin::UsersController} + Devise
 * ({@code database_authenticatable}/{@code confirmable}/{@code reconfirmable}) + {@code User} model.
 */
class AdminUsersWriteControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Value("${ffcrm.security.legacy-password.stretches:20}")
    private int stretches;

    private User alice;
    private User admin;
    private String aliceBearer;
    private String adminBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice", "Alice", "A", false);
        admin = user("root_admin", "Admin", "Root", true);
        aliceBearer = bearer(alice);
        adminBearer = bearer(admin);
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void userWritesRequireAuthenticationAndAdminRole() throws Exception {
        String body = "{\"user\":{\"username\":\"x\",\"email\":\"x@example.test\",\"password\":\"secret12\"}}";
        List<MockHttpServletRequestBuilder> routes = List.of(
            post("/api/v1/admin/users").contentType(MediaType.APPLICATION_JSON).content(body),
            put("/api/v1/admin/users/{id}", alice.getId()).contentType(MediaType.APPLICATION_JSON).content(body),
            delete("/api/v1/admin/users/{id}", alice.getId()),
            put("/api/v1/admin/users/{id}/suspend", alice.getId()),
            put("/api/v1/admin/users/{id}/reactivate", alice.getId()));
        for (MockHttpServletRequestBuilder route : routes) {
            mockMvc.perform(route).andExpect(status().isUnauthorized());
            mockMvc.perform(route.header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isForbidden());
        }
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("versions")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT suspended_at FROM users WHERE id = ?", Object.class,
            alice.getId())).isNull();
    }

    @Test
    void createNormalizesEmailHashesPasswordAssignsGroupsAndRecordsCreateVersion() throws Exception {
        long groupId = group("Sales");

        MvcResult result = mockMvc.perform(adminJson(post("/api/v1/admin/users"))
                .content("{\"user\":{\"username\":\"dave\",\"email\":\"  Dave@X.Example \","
                    + "\"first_name\":\"Dave\",\"last_name\":\"Dee\",\"admin\":\"0\","
                    + "\"password\":\"secret12\",\"password_confirmation\":\"secret12\","
                    + "\"group_ids\":[\"" + groupId + "\"]}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$[0]").value("Dave"))
            .andExpect(jsonPath("$.length()").value(1))
            .andReturn();
        long id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = 'dave'", Long.class);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/users/" + id);

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id);
        assertThat(row.get("email")).isEqualTo("dave@x.example");
        assertThat(row.get("admin")).isEqualTo(false);
        assertThat(row.get("confirmed_at")).isNull();
        assertThat(row.get("suspended_at")).isNull();
        assertThat(row.get("confirmation_sent_at")).isNotNull();
        assertThat((String) row.get("confirmation_token")).hasSize(20).doesNotContainPattern("[lIO0]");
        assertThat(row.get("subscribe_to_comment_replies")).isEqualTo(true);
        assertThat(row.get("receive_assigned_notifications")).isEqualTo(true);
        String salt = (String) row.get("password_salt");
        assertThat(salt).hasSize(20);
        assertThat(row.get("encrypted_password"))
            .isEqualTo(new AuthlogicSha512PasswordEncoder(stretches).digest("secret12", salt));
        assertThat(jdbcTemplate.queryForList("SELECT group_id FROM groups_users WHERE user_id = ?", Long.class, id))
            .containsExactly(groupId);

        Map<String, Object> version = jdbcTemplate.queryForMap("SELECT * FROM versions");
        assertThat(version.get("item_type")).isEqualTo("User");
        assertThat(((Number) version.get("item_id")).longValue()).isEqualTo(id);
        assertThat(version.get("event")).isEqualTo("create");
        assertThat(version.get("whodunnit")).isEqualTo(admin.getId().toString());
        assertThat(version.get("object")).isNull();
        assertThat((String) version.get("object_changes"))
            .startsWith("---\nid:\n-\n- " + id + "\n")
            .contains("username:\n- ''\n- dave\n")
            .contains("first_name:\n-\n- Dave\n")
            .contains("confirmation_token:\n-\n- " + row.get("confirmation_token") + "\n")
            .doesNotContain("\nadmin:")
            .doesNotContain("last_sign_in_at")
            .doesNotContain("subscribe_to_comment_replies");
    }

    @Test
    void createFallsBackToUsernameAndSuspendsNonAdminsWhenSignupNeedsApproval() throws Exception {
        jdbcTemplate.update("INSERT INTO settings (name, value, created_at, updated_at) "
            + "VALUES ('user_signup', E'--- :needs_approval\\n', now(), now())");

        mockMvc.perform(adminJson(post("/api/v1/admin/users"))
                .content("{\"user\":{\"username\":\"erin\",\"email\":\"erin@example.test\","
                    + "\"password\":\"secret12\"}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$[0]").value("erin"));
        mockMvc.perform(adminJson(post("/api/v1/admin/users"))
                .content("{\"user\":{\"username\":\"frank\",\"email\":\"frank@example.test\","
                    + "\"password\":\"secret12\",\"admin\":\"1\"}}"))
            .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject("SELECT suspended_at FROM users WHERE username = 'erin'",
            Object.class)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT suspended_at FROM users WHERE username = 'frank'",
            Object.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT admin FROM users WHERE username = 'frank'",
            Boolean.class)).isTrue();
    }

    @Test
    void createValidationFailureReturnsRailsMessagesAndPersistsNothing() throws Exception {
        mockMvc.perform(adminJson(post("/api/v1/admin/users"))
                .content("{\"user\":{\"username\":\"\",\"email\":\"not-an-email\","
                    + "\"password\":\"abc\",\"password_confirmation\":\"xyz\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.email", contains("is invalid")))
            .andExpect(jsonPath("$.errors.username", contains("^Please specify username.", "is invalid")))
            .andExpect(jsonPath("$.errors.password_confirmation", contains("doesn't match Password")))
            .andExpect(jsonPath("$.errors.password").doesNotExist());

        // Rails: `return {} unless params[:user]` → User.new({}) fails validation (422, not 400).
        for (String body : List.of("{}", "")) {
            mockMvc.perform(adminJson(post("/api/v1/admin/users")).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.email", contains("^Please specify email address.",
                    "is too short (minimum is 3 characters)", "is invalid")))
                .andExpect(jsonPath("$.errors.username", contains("^Please specify username.", "is invalid")))
                .andExpect(jsonPath("$.errors.password", contains("can't be blank")));
        }

        // uniqueness is case-insensitive on both username and email
        mockMvc.perform(adminJson(post("/api/v1/admin/users"))
                .content("{\"user\":{\"username\":\"ALICE\",\"email\":\"Alice@Example.Test\","
                    + "\"password\":\"secret12\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.email", contains("^There is another user with the same email.")))
            .andExpect(jsonPath("$.errors.username", contains("^This username is already taken.")));

        assertThat(count("users")).isEqualTo(2);
        assertThat(count("versions")).isZero();
    }

    @Test
    void updatePostponesEmailChangeReplacesGroupsAndRecordsUpdateVersionLedByEmail() throws Exception {
        User bob = user("bob", "Bob", "B", false);
        long g1 = group("G1");
        long g2 = group("G2");
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", g1, bob.getId());

        mockMvc.perform(adminJson(put("/api/v1/admin/users/{id}", bob.getId()))
                .content("{\"user\":{\"first_name\":\"Bobby\",\"email\":\"Bob2@Example.Test\","
                    + "\"group_ids\":[" + g2 + "]}}"))
            .andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", bob.getId());
        assertThat(row.get("email")).isEqualTo("bob@example.test");
        assertThat(row.get("unconfirmed_email")).isEqualTo("bob2@example.test");
        assertThat(row.get("confirmation_token")).isNotNull();
        assertThat(row.get("first_name")).isEqualTo("Bobby");
        assertThat(jdbcTemplate.queryForList("SELECT group_id FROM groups_users WHERE user_id = ?", Long.class,
            bob.getId())).containsExactly(g2);

        Map<String, Object> version = jdbcTemplate.queryForMap("SELECT * FROM versions");
        assertThat(version.get("item_type")).isEqualTo("User");
        assertThat(version.get("event")).isEqualTo("update");
        assertThat(version.get("whodunnit")).isEqualTo(admin.getId().toString());
        assertThat((String) version.get("object")).startsWith("---\nemail: bob@example.test\nfirst_name: Bob\nid: ");
        assertThat((String) version.get("object_changes"))
            .contains("first_name:\n- Bob\n- Bobby\n")
            .contains("unconfirmed_email:\n-\n- bob2@example.test\n")
            .doesNotContain("\nemail:");

        mockMvc.perform(adminJson(put("/api/v1/admin/users/999999")).content("{\"user\":{\"first_name\":\"X\"}}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(adminJson(put("/api/v1/admin/users/{id}", bob.getId()))
                .content("{\"user\":{\"group_ids\":[999999]}}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateWritesGroupMembershipsEvenWhenValidationFails() throws Exception {
        User bob = user("bob", "Bob", "B", false);
        long g1 = group("G1");

        mockMvc.perform(adminJson(put("/api/v1/admin/users/{id}", bob.getId()))
                .content("{\"user\":{\"username\":\"\",\"group_ids\":[" + g1 + "]}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.username", contains("^Please specify username.", "is invalid")));

        assertThat(jdbcTemplate.queryForObject("SELECT username FROM users WHERE id = ?", String.class, bob.getId()))
            .isEqualTo("bob");
        assertThat(jdbcTemplate.queryForList("SELECT group_id FROM groups_users WHERE user_id = ?", Long.class,
            bob.getId())).containsExactly(g1);
        assertThat(count("versions")).isZero();
    }

    @Test
    void updatePasswordRequiresMatchingConfirmationAndRehashesWithFreshSalt() throws Exception {
        User bob = user("bob", "Bob", "B", false);

        mockMvc.perform(adminJson(put("/api/v1/admin/users/{id}", bob.getId()))
                .content("{\"user\":{\"password\":\"newpass12\",\"password_confirmation\":\"other\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.password_confirmation", contains("doesn't match Password")));
        mockMvc.perform(adminJson(put("/api/v1/admin/users/{id}", bob.getId()))
                .content("{\"user\":{\"password_confirmation\":\"x\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.password", contains("can't be blank")));
        assertThat(jdbcTemplate.queryForObject("SELECT encrypted_password FROM users WHERE id = ?", String.class,
            bob.getId())).isEqualTo("encrypted");

        mockMvc.perform(adminJson(put("/api/v1/admin/users/{id}", bob.getId()))
                .content("{\"user\":{\"password\":\"newpass12\",\"password_confirmation\":\"newpass12\"}}"))
            .andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", bob.getId());
        String salt = (String) row.get("password_salt");
        assertThat(salt).hasSize(20).isNotEqualTo("salt");
        assertThat(row.get("encrypted_password"))
            .isEqualTo(new AuthlogicSha512PasswordEncoder(stretches).digest("newpass12", salt));
        assertThat(jdbcTemplate.queryForObject("SELECT object_changes FROM versions", String.class))
            .contains("password_salt:\n- salt\n- " + salt + "\n")
            .contains("encrypted_password:\n- encrypted\n- ");
    }

    @Test
    void suspendIsNoOpForSelfAndReactivateClearsSuspension() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{id}/suspend", admin.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT suspended_at FROM users WHERE id = ?", Object.class,
            admin.getId())).isNull();
        assertThat(count("versions")).isZero();

        mockMvc.perform(put("/api/v1/admin/users/{id}/suspend", alice.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT suspended_at FROM users WHERE id = ?", Object.class,
            alice.getId())).isNotNull();
        Map<String, Object> version = jdbcTemplate.queryForMap("SELECT * FROM versions");
        assertThat(version.get("event")).isEqualTo("update");
        assertThat((String) version.get("object")).startsWith("---\nsuspended_at:\n");
        assertThat((String) version.get("object_changes")).contains("suspended_at:\n-\n- ");

        mockMvc.perform(put("/api/v1/admin/users/{id}/reactivate", alice.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT suspended_at FROM users WHERE id = ?", Object.class,
            alice.getId())).isNull();
        assertThat(count("versions")).isEqualTo(2);

        // reactivating an active user changes nothing → no save, no version
        mockMvc.perform(put("/api/v1/admin/users/{id}/reactivate", alice.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(count("versions")).isEqualTo(2);
        mockMvc.perform(put("/api/v1/admin/users/999999/suspend").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void destroyIsGuardedForSelfAndAssetOwnersAndCascadesJoinRows() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/users/{id}", admin.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(count("users")).isEqualTo(2);

        User bob = user("bob", "Bob", "B", false);
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_type, commentable_id, comment, "
            + "created_at, updated_at) VALUES (?, 'Account', 1, 'hi', now(), now())", bob.getId());
        mockMvc.perform(delete("/api/v1/admin/users/{id}", bob.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());
        assertThat(count("users")).isEqualTo(3);
        assertThat(count("versions")).isZero();

        User carol = user("carol", "Carol", "C", false);
        long g1 = group("G1");
        jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)", g1, carol.getId());
        jdbcTemplate.update("INSERT INTO preferences (user_id, name, value, created_at, updated_at) "
            + "VALUES (?, 'locale', 'en', now(), now())", carol.getId());
        mockMvc.perform(delete("/api/v1/admin/users/{id}", carol.getId())
                .header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class,
            carol.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM groups_users WHERE user_id = ?", Integer.class,
            carol.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM preferences WHERE user_id = ?", Integer.class,
            carol.getId())).isZero();
        assertThat(count("groups")).isEqualTo(1);
        Map<String, Object> version = jdbcTemplate.queryForMap("SELECT * FROM versions");
        assertThat(version.get("event")).isEqualTo("destroy");
        assertThat(version.get("item_type")).isEqualTo("User");
        assertThat(((Number) version.get("item_id")).longValue()).isEqualTo(carol.getId());
        assertThat((String) version.get("object")).contains("\nusername: carol\n");
        assertThat((String) version.get("object_changes")).contains("username:\n- carol\n-\n");

        mockMvc.perform(delete("/api/v1/admin/users/999999").header(HttpHeaders.AUTHORIZATION, adminBearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void entityAttributesDumpEveryUsersColumnForPaperTrail() {
        List<String> columns = jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
            + "WHERE table_schema = current_schema() AND table_name = 'users'", String.class);
        User fresh = userRepository.findById(alice.getId()).orElseThrow();

        assertThat(new HashSet<>(EntityAttributes.of(fresh).keySet())).isEqualTo(new HashSet<>(columns));
    }

    private MockHttpServletRequestBuilder adminJson(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, adminBearer).contentType(MediaType.APPLICATION_JSON);
    }

    private long group(String name) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO groups (name, created_at, updated_at) VALUES (?, now(), now()) RETURNING id",
            Long.class, name);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private User user(String username, String firstName, String lastName, boolean isAdmin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(isAdmin);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
    }

    private void clearData() {
        for (String table : List.of("versions", "comments", "preferences", "permissions", "groups_users",
            "settings", "groups", "users")) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
    }
}
