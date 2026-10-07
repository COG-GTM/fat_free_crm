package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RailsJsonWriterTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RailsJsonWriter jsonWriter;

    @Autowired
    private RailsResources railsResources;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private TaggingRepository taggingRepository;

    private Account account;
    private User owner;

    @BeforeEach
    void seedAccount() {
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");

        owner = new User();
        owner.setUsername("json_writer");
        owner.setEncryptedPassword("encrypted");
        owner.setPasswordSalt("salt");
        userRepository.saveAndFlush(owner);

        account = new Account();
        account.setName("Serializer Corp");
        account.setEmail("serialize@example.test");
        account.setCategory("customer");
        account.setAccess("Public");
        account.setUser(owner);
        account.setLatitude(new BigDecimal("40.712800"));
        account.setLongitude(BigDecimal.ZERO);
        Instant timestamp = Instant.parse("2025-01-03T00:00:00.123987Z");
        account.setCreatedAt(timestamp);
        account.setUpdatedAt(timestamp);
        accountRepository.saveAndFlush(account);
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?",
            "---\n- 7\n- 8\n", account.getId());

        Tag vip = new Tag();
        vip.setName("vip");
        tagRepository.saveAndFlush(vip);
        Tag west = new Tag();
        west.setName("west");
        tagRepository.saveAndFlush(west);
        saveTagging(vip);
        saveTagging(west);
        Tag owned = new Tag();
        owned.setName("owned");
        tagRepository.saveAndFlush(owned);
        saveTagging(owned, owner);
    }

    @Test
    void serializesEveryDatabaseColumnInOrderWithRailsTypesAndOrderedTags() {
        ObjectNode json = jsonWriter.writeOne(railsResources.account, account.getId());
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = 'accounts' ORDER BY ordinal_position",
            String.class
        );
        List<String> expected = new ArrayList<>(columns);
        expected.add("tag_list");

        assertThat(fieldNames(json)).containsExactlyElementsOf(expected);
        assertThat(json.path("id").isNumber()).isTrue();
        assertThat(json.path("rating").isNumber()).isTrue();
        assertThat(json.path("created_at").asText()).isEqualTo("2025-01-03T00:00:00.123Z");
        assertThat(json.path("latitude").asText()).isEqualTo("40.7128");
        assertThat(json.path("longitude").asText()).isEqualTo("0.0");
        assertThat(json.path("subscribed_users").toString()).isEqualTo("[7,8]");
        assertThat(json.path("tag_list").toString()).isEqualTo("[\"vip\",\"west\"]");
        assertThat(json.path("website").isNull()).isTrue();
    }

    @Test
    void serializesRuntimeCheckboxDefinitionsAndDynamicColumns() {
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_tier varchar");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_flags text");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_enabled boolean");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_score double precision");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_day date");
        jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN cf_data jsonb");
        Long groupId = jdbcTemplate.queryForObject(
            "INSERT INTO field_groups (name, klass_name) VALUES ('Account fields', 'Account') RETURNING id",
            Long.class
        );
        jdbcTemplate.update(
            "INSERT INTO fields (field_group_id, name, \"as\") VALUES (?, 'cf_tier', 'text'), (?, 'cf_flags', "
                + "'check_boxes')",
            groupId,
            groupId
        );
        jdbcTemplate.update(
            "UPDATE accounts SET cf_tier = ?, cf_flags = ?, cf_enabled = ?, cf_score = ?, "
                + "cf_day = ?, cf_data = ?::jsonb WHERE id = ?",
            "Gold",
            "---\n- urgent\n- renewal\n",
            true,
            3.5d,
            java.sql.Date.valueOf("2025-01-03"),
            "{\"source\":\"rails\"}",
            account.getId()
        );

        ObjectNode json = jsonWriter.writeOne(railsResources.account, account.getId());

        assertThat(json.path("cf_tier").asText()).isEqualTo("Gold");
        assertThat(json.path("cf_flags").toString()).isEqualTo("[\"urgent\",\"renewal\"]");
        assertThat(json.path("cf_enabled").asBoolean()).isTrue();
        assertThat(json.path("cf_score").asDouble()).isEqualTo(3.5d);
        assertThat(json.path("cf_day").asText()).isEqualTo("2025-01-03");
        assertThat(json.path("cf_data").path("source").asText()).isEqualTo("rails");
    }

    @Test
    void omitsRailsIgnoredCustomFieldsFromListAndShow() {
        try {
            jdbcTemplate.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS custom_fields jsonb");
            jdbcTemplate.update(
                "UPDATE accounts SET custom_fields = ?::jsonb WHERE id = ?",
                "{\"x\":1}",
                account.getId()
            );

            List<ObjectNode> items = jsonWriter.write(railsResources.account, List.of(account.getId()));
            ObjectNode show = jsonWriter.writeOne(railsResources.account, account.getId());

            assertThat(items).hasSize(1);
            assertThat(items.getFirst().has("custom_fields")).isFalse();
            assertThat(items.getFirst().path("name").asText()).isEqualTo("Serializer Corp");
            assertThat(show.has("custom_fields")).isFalse();
            assertThat(show.path("name").asText()).isEqualTo("Serializer Corp");
        } finally {
            jdbcTemplate.execute("ALTER TABLE accounts DROP COLUMN IF EXISTS custom_fields");
        }
    }

    @Test
    void omitsSensitiveAndDescriptorExcludedColumns() {
        RailsResource resource = new RailsResource(
            "Account",
            "accounts",
            Account.class,
            Set.of(),
            false,
            Set.of("internal_value"),
            "accounts",
            ignored -> "",
            java.util.Map.of()
        );

        assertThat(RailsJsonWriter.shouldSerializeColumn(resource, "password_digest")).isFalse();
        assertThat(RailsJsonWriter.shouldSerializeColumn(resource, "api_token")).isFalse();
        assertThat(RailsJsonWriter.shouldSerializeColumn(resource, "password_salt")).isFalse();
        assertThat(RailsJsonWriter.shouldSerializeColumn(resource, "internal_value")).isFalse();
        assertThat(RailsJsonWriter.shouldSerializeColumn(resource, "name")).isTrue();
    }

    private void saveTagging(Tag tag) {
        saveTagging(tag, null);
    }

    private void saveTagging(Tag tag, User tagger) {
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Account");
        tagging.setTaggableId(account.getId().intValue());
        tagging.setContext("tags");
        if (tagger != null) {
            tagging.setTaggerType("User");
            tagging.setTaggerId(tagger.getId().intValue());
        }
        taggingRepository.saveAndFlush(tagging);
    }

    private static List<String> fieldNames(ObjectNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
