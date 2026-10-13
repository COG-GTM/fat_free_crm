package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.json.JsonCompareMode;

/**
 * {@code POST/PUT/DELETE /api/v1/accounts} pinned to Rails {@code AccountsController} +
 * {@code Account} model: CanCan {@code attributes_for} defaults, every validation message,
 * {@code uses_user_permissions} writes (including the immediate permission delete that survives a
 * 422), acts_as_taggable, {@code add_comment_by_user}, dependent destroys and PaperTrail rows.
 */
class AccountsWriteControllerIntegrationTest extends AbstractEntityWriteIntegrationTest {

    @Test
    void createDefaultsAccessOwnerAndAssigneeFromCurrentUserAndWritesCreateVersion()
        throws Exception {
        String response = mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Acme\",\"website\":\"acme.example\"}}"))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, Matchers.startsWith("/api/v1/accounts/")))
            .andExpect(jsonPath("$.name").value("Acme"))
            .andExpect(jsonPath("$.access").value("Public"))
            .andExpect(jsonPath("$.user_id").value(alice.getId()))
            .andReturn().getResponse().getContentAsString();
        long id = json(response).path("id").asLong();

        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("access")).isEqualTo("Public");
        assertThat(row.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("assigned_to")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("website")).isEqualTo("acme.example");
        assertThat(row.get("rating")).isEqualTo(0);

        List<Map<String, Object>> versions = versions("Account", id);
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.get(0);
        assertThat(version.get("event")).isEqualTo("create");
        assertThat(version.get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat(version.get("object")).isNull();
        String changes = (String) version.get("object_changes");
        assertThat(changes).startsWith("---\n");
        assertThat(changes).contains("name:\n- ''\n- Acme\n");
        assertThat(changes).contains("website:\n-\n- acme.example\n");
        // Attributes equal to their column default (access, rating, counters) are omitted.
        assertThat(changes).doesNotContain("\naccess:\n")
            .doesNotContain("\nrating:\n")
            .doesNotContain("subscribed_users")
            .doesNotContain("custom_fields");
    }

    @Test
    void createHonoursExplicitOwnerAccessAndAssigneeInsteadOfCanCanDefaults() throws Exception {
        long id = create(aliceBearer, "{\"account\":{\"name\":\"Bob owns\",\"user_id\":" + bob.getId()
            + ",\"access\":\"Private\",\"assigned_to\":null}}");
        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("user_id")).isEqualTo(bob.getId().intValue());
        assertThat(row.get("access")).isEqualTo("Private");
        assertThat(row.get("assigned_to")).isNull();
    }

    @Test
    void createWithBlankNameReturnsRailsMessageAndWritesNothing() throws Exception {
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"  \"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"^Please specify account name.\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer).content("{\"account\":{}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify account name."));
        assertThat(count("accounts")).isZero();
        assertThat(count("versions")).isZero();
    }

    @Test
    void createDuplicateNameIsTakenUnlessUniqueNamesSettingIsOff() throws Exception {
        account("Acme", bob, "Private");
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Acme\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"name\":[\"has already been taken\"]}}",
                JsonCompareMode.STRICT));
        assertThat(count("accounts")).isEqualTo(1);

        setting("require_unique_account_names", "--- false\n");
        create(aliceBearer, "{\"account\":{\"name\":\"Acme\"}}");
        assertThat(count("accounts", "name = 'Acme'")).isEqualTo(2);
    }

    @Test
    void createValidatesRatingCategoryAndCoordinatesLikeTheModel() throws Exception {
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Bad\",\"rating\":6,\"category\":\"bogus\","
                    + "\"latitude\":\"95\",\"longitude\":-181}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{"
                + "\"rating\":[\"is not included in the list\"],"
                + "\"category\":[\"is not included in the list\"],"
                + "\"latitude\":[\"must be less than or equal to 90\"],"
                + "\"longitude\":[\"must be greater than or equal to -180\"]}}",
                JsonCompareMode.STRICT));
        long id = create(aliceBearer, "{\"account\":{\"name\":\"Good\",\"rating\":5,"
            + "\"category\":\"customer\",\"latitude\":\"90\",\"longitude\":\"-180\"}}");
        assertThat(row("accounts", id).get("rating")).isEqualTo(5);
        assertThat(row("accounts", id).get("category")).isEqualTo("customer");
    }

    @Test
    void createBlankCategoryIsNullifiedBeforeSave() throws Exception {
        long id = create(aliceBearer, "{\"account\":{\"name\":\"Blank cat\",\"category\":\"\"}}");
        assertThat(row("accounts", id).get("category")).isNull();
    }

    @Test
    void createSharedWithoutUsersFailsWithShareMessageAndWritesNoPermissions() throws Exception {
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Shared\",\"access\":\"Shared\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().json("{\"errors\":{\"access\":"
                + "[\"^Please specify users with whom to share the account.\"]}}",
                JsonCompareMode.STRICT));
        mockMvc.perform(authed(post("/api/v1/accounts"), aliceBearer)
                .content("{\"account\":{\"name\":\"Shared\",\"access\":\"Shared\",\"user_ids\":[\"\"]}}"))
            .andExpect(status().isUnprocessableEntity());
        assertThat(count("accounts")).isZero();
        assertThat(count("permissions")).isZero();
    }

    @Test
    void createSharedWithUserAndGroupIdsInsertsPermissionRowsAfterTheSave() throws Exception {
        long groupId = group("sales", bob);
        long id = create(aliceBearer, "{\"account\":{\"name\":\"Shared\",\"access\":\"Shared\","
            + "\"user_ids\":[" + bob.getId() + ",\"" + bob.getId() + "\",\"\"],"
            + "\"group_ids\":\"" + groupId + "\"}}");
        assertThat(permissionUserIds("Account", id)).containsExactly(bob.getId());
        assertThat(jdbcTemplate.queryForList("SELECT group_id FROM permissions WHERE asset_type ="
            + " 'Account' AND asset_id = ? AND group_id IS NOT NULL", Long.class, id))
            .containsExactly(groupId);
        assertThat(count("permissions")).isEqualTo(2);
        assertThat(row("accounts", id).get("access")).isEqualTo("Shared");
    }

    @Test
    void createUserIdsAreIgnoredWhenAccessIsNotShared() throws Exception {
        long id = create(aliceBearer, "{\"account\":{\"name\":\"Public\",\"access\":\"Public\","
            + "\"user_ids\":[" + bob.getId() + "]}}");
        assertThat(count("permissions")).isZero();
        assertThat(row("accounts", id).get("access")).isEqualTo("Public");
    }

    @Test
    void updateAccessAwayFromSharedDeletesPermissionsEvenWhenTheSaveFails() throws Exception {
        long id = account("Shared", alice, "Shared");
        permission("Account", id, bob, null);
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"access\":\"Public\",\"name\":\"\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify account name."));
        // Rails access= destroys the rows outside the (failed) save transaction.
        assertThat(count("permissions")).isZero();
        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("name")).isEqualTo("Shared");
        assertThat(row.get("access")).isEqualTo("Shared");
        assertThat(versions("Account", id)).isEmpty();
    }

    @Test
    void updateUserIdsReplacesStalePermissionsAndKeepsExistingOnes() throws Exception {
        long id = account("Shared", alice, "Shared");
        permission("Account", id, bob, null);
        long before = jdbcTemplate.queryForObject(
            "SELECT id FROM permissions WHERE user_id = ?", Long.class, bob.getId());
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"access\":\"Shared\",\"user_ids\":[" + bob.getId() + ","
                    + admin.getId() + "]}}"))
            .andExpect(status().isNoContent());
        assertThat(permissionUserIds("Account", id)).containsExactly(bob.getId(), admin.getId());
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM permissions WHERE user_id = ?",
            Long.class, bob.getId())).isEqualTo(before);

        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"access\":\"Shared\",\"user_ids\":[" + admin.getId() + "]}}"))
            .andExpect(status().isNoContent());
        assertThat(permissionUserIds("Account", id)).containsExactly(admin.getId());
    }

    @Test
    void updateToSharedWithoutAnyPermissionIsRejected() throws Exception {
        long id = account("Public", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"access\":\"Shared\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.access[0]")
                .value("^Please specify users with whom to share the account."));
        assertThat(row("accounts", id).get("access")).isEqualTo("Public");
    }

    @Test
    void updateWritesOnlyChangedColumnsIntoTheUpdateVersion() throws Exception {
        long id = account("Acme", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"name\":\"Acme Ltd\",\"rating\":3,\"access\":\"Public\"}}"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        Map<String, Object> row = row("accounts", id);
        assertThat(row.get("name")).isEqualTo("Acme Ltd");
        assertThat(row.get("rating")).isEqualTo(3);

        List<Map<String, Object>> versions = versions("Account", id);
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.get(0);
        assertThat(version.get("event")).isEqualTo("update");
        assertThat(version.get("whodunnit")).isEqualTo(alice.getId().toString());
        assertThat((String) version.get("object")).startsWith("---\n").contains("name: Acme\n")
            .contains("id: " + id + "\n");
        String changes = (String) version.get("object_changes");
        assertThat(changes).contains("name:\n- Acme\n- Acme Ltd\n").contains("rating:\n- 0\n- 3\n")
            .doesNotContain("\naccess:\n").doesNotContain("subscribed_users");
    }

    @Test
    void updateWithNoEffectiveChangeWritesNoVersion() throws Exception {
        long id = account("Acme", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"name\":\"Acme\"}}"))
            .andExpect(status().isNoContent());
        assertThat(versions("Account", id)).isEmpty();
    }

    @Test
    void updateDuplicateNameExcludesTheRecordItself() throws Exception {
        long id = account("Acme", alice, "Public");
        account("Other", alice, "Public");
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"name\":\"Acme\",\"phone\":\"555\"}}"))
            .andExpect(status().isNoContent());
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"name\":\"Other\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name[0]").value("has already been taken"));
    }

    @Test
    void tagListCreatesTaggingsAndCountersThenReplacesThemOnUpdate() throws Exception {
        long id = create(aliceBearer, "{\"account\":{\"name\":\"Tagged\",\"tag_list\":\"hot, vip\"}}");
        assertThat(tagNames("Account", id)).containsExactly("hot", "vip");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT taggings_count FROM tags WHERE name = 'hot'", Integer.class)).isEqualTo(1);
        assertThat((String) versions("Account", id).get(0).get("object_changes"))
            .contains("tag_list:\n- []\n- - hot\n  - vip\n");

        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
                .content("{\"account\":{\"tag_list\":[\"vip\",\"new\"]}}"))
            .andExpect(status().isNoContent());
        assertThat(tagNames("Account", id)).containsExactly("vip", "new");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT taggings_count FROM tags WHERE name = 'hot'", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT taggings_count FROM tags WHERE name = 'vip'", Integer.class)).isEqualTo(1);
        assertThat(count("taggings")).isEqualTo(2);
    }

    @Test
    void commentBodyCreatesACommentByTheCurrentUserAndSubscribesThem() throws Exception {
        long id = create(aliceBearer,
            "{\"account\":{\"name\":\"Noted\"},\"comment_body\":\"first note\"}");
        Map<String, Object> comment = jdbcTemplate.queryForMap(
            "SELECT * FROM comments WHERE commentable_type = 'Account' AND commentable_id = ?", id);
        assertThat(comment.get("comment")).isEqualTo("first note");
        assertThat(comment.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(subscribedUsers("accounts", id)).containsExactly(alice.getId());
        assertThat(events("Comment", ((Number) comment.get("id")).longValue()))
            .containsExactly("create");

        long quiet = create(aliceBearer, "{\"account\":{\"name\":\"Quiet\"},\"comment_body\":\"  \"}");
        assertThat(count("comments", "commentable_id = ?", quiet)).isZero();
    }

    @Test
    void destroyRemovesDependentsWritesDestroyVersionsAndKeepsLinkedContacts() throws Exception {
        long id = account("Doomed", alice, "Public");
        long contactId = contact("Still", "Here", alice, "Public");
        linkAccountContact(id, contactId);
        long taskId = task("Call", alice, "Account", id);
        jdbcTemplate.update("INSERT INTO comments (user_id, commentable_type, commentable_id,"
            + " comment, created_at, updated_at) VALUES (?, 'Account', ?, 'bye', now(), now())",
            alice.getId(), id);
        create(aliceBearer, "{\"account\":{\"name\":\"Tag donor\",\"tag_list\":\"shared-tag\"}}");
        mockMvc.perform(authed(put("/api/v1/accounts/" + id), aliceBearer)
            .content("{\"account\":{\"tag_list\":\"shared-tag\"}}")).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT taggings_count FROM tags WHERE name = 'shared-tag'", Integer.class)).isEqualTo(2);

        mockMvc.perform(authed(delete("/api/v1/accounts/" + id), aliceBearer))
            .andExpect(status().isNoContent());

        assertThat(exists("accounts", id)).isFalse();
        assertThat(exists("contacts", contactId)).isTrue();
        assertThat(exists("tasks", taskId)).isFalse();
        assertThat(count("account_contacts")).isZero();
        assertThat(count("comments", "commentable_id = ?", id)).isZero();
        assertThat(tagNames("Account", id)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT taggings_count FROM tags WHERE name = 'shared-tag'", Integer.class)).isEqualTo(1);
        assertThat(events("Account", id)).containsExactly("update", "destroy");
        assertThat(events("Task", taskId)).containsExactly("destroy");
        assertThat(count("versions", "item_type = 'AccountContact' AND event = 'destroy'"))
            .isEqualTo(1);
        assertThat(count("versions", "item_type = 'Comment' AND event = 'destroy'")).isEqualTo(1);
        Map<String, Object> destroy = versions("Account", id).get(1);
        assertThat((String) destroy.get("object")).contains("name: Doomed\n")
            .contains("contacts_count: 1\n");
        // PaperTrail >= 10 dumps every attribute → nil on destroy.
        assertThat((String) destroy.get("object_changes")).contains("name:\n- Doomed\n-\n")
            .contains("id:\n- " + id + "\n-\n");
    }

    private long create(String bearer, String body) throws Exception {
        String response = mockMvc.perform(authed(post("/api/v1/accounts"), bearer).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json(response).path("id").asLong();
    }
}
