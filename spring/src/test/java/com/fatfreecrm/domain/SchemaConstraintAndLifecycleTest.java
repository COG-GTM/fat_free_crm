package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.EmailRepository;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.VersionRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes Spring-created rows through the Flyway baseline (the Rails schema) and checks that the
 * database rejects what Rails' schema rejects, and that raw column bytes match what Rails writes.
 */
class SchemaConstraintAndLifecycleTest extends AbstractPostgresIntegrationTest {

    private static final Instant RAILS_CREATED_AT = Instant.parse("2025-01-02T03:04:05.000001Z");
    private static final Instant RAILS_UPDATED_AT = Instant.parse("2025-01-02T03:04:06.999999Z");

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private PreferenceRepository preferenceRepository;

    @Autowired
    private SettingRepository settingRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private TaggingRepository taggingRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private VersionRepository versionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void accountsNeedANameOfAtMost64CharactersLikeTheRailsSchema() {
        assertThatThrownBy(() -> rolledBack(() -> {
            Account account = new Account();
            account.setName(null);
            accountRepository.saveAndFlush(account);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> rolledBack(() -> {
            Account account = new Account();
            account.setName("x".repeat(65));
            accountRepository.saveAndFlush(account);
        })).isInstanceOf(DataIntegrityViolationException.class);

        rolledBack(() -> {
            Account account = new Account();
            account.setName("x".repeat(64));
            accountRepository.saveAndFlush(account);
            assertThat(account.getId()).isNotNull();
            assertThat(account.getAccess()).isEqualTo("Public");
        });
    }

    @Test
    void tagNamesAndTaggingsAreUniqueLikeTheRailsIndexes() {
        assertThatThrownBy(() -> rolledBack(() -> {
            tagRepository.saveAndFlush(tag("duplicate"));
            tagRepository.saveAndFlush(tag("duplicate"));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> rolledBack(() -> {
            Tag tag = tagRepository.saveAndFlush(tag("once"));
            taggingRepository.saveAndFlush(tagging(tag, 7, "tags"));
            taggingRepository.saveAndFlush(tagging(tag, 7, "tags"));
        })).isInstanceOf(DataIntegrityViolationException.class);

        rolledBack(() -> {
            Tag tag = tagRepository.saveAndFlush(tag("once"));
            taggingRepository.saveAndFlush(tagging(tag, 7, "tags"));
            taggingRepository.saveAndFlush(tagging(tag, 8, "tags"));
            taggingRepository.saveAndFlush(tagging(tag, 7, "skills"));
            assertThat(taggingRepository.findByTaggableTypeAndTaggableId("Account", 7)).hasSize(2);
        });
    }

    @Test
    void emailsAndVersionsRejectMissingRequiredRailsColumns() {
        assertThatThrownBy(() -> rolledBack(() -> {
            Email email = new Email();
            email.setSentFrom("owner@example.test");
            email.setSentTo("assignee@example.test");
            emailRepository.saveAndFlush(email);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> rolledBack(() -> {
            Version version = new Version();
            version.setItemId(1);
            version.setEvent("create");
            versionRepository.saveAndFlush(version);
        })).isInstanceOf(DataIntegrityViolationException.class);

        rolledBack(() -> {
            Version version = new Version();
            version.setItemType("Account");
            version.setItemId(1);
            version.setEvent("create");
            versionRepository.saveAndFlush(version);
            assertThat(version.getId()).isNotNull();
            assertThat(version.getCreatedAt()).isNull();
        });
    }

    @Test
    void subscribedUsersRoundTripThroughTheRailsYamlColumn() {
        rolledBack(() -> {
            Account account = new Account();
            account.setName("Subscribed");
            account.setSubscribedUsers(List.of(2L, 1L, 2L));
            accountRepository.saveAndFlush(account);
            assertThat(rawColumn("accounts", "subscribed_users", account.getId())).isEqualTo("---\n- 2\n- 1\n- 2\n");

            entityManager.clear();
            Account reloaded = accountRepository.findById(account.getId()).orElseThrow();
            assertThat(reloaded.getSubscribedUsers()).containsExactly(2L, 1L, 2L);

            reloaded.setSubscribedUsers(List.of());
            accountRepository.saveAndFlush(reloaded);
            assertThat(rawColumn("accounts", "subscribed_users", account.getId())).isNull();

            entityManager.clear();
            assertThat(accountRepository.findById(account.getId()).orElseThrow().getSubscribedUsers()).isEmpty();
        });

        rolledBack(() -> {
            Task task = new Task();
            task.setName("Subscribed task");
            task.setSubscribedUsers(List.of(5L));
            taskRepository.saveAndFlush(task);
            assertThat(rawColumn("tasks", "subscribed_users", task.getId())).isEqualTo("---\n- 5\n");
            assertThat(rawColumn("tasks", "asset_type", task.getId())).isNull();
        });
    }

    @Test
    void preferencesPersistTheRailsBase64JsonEncoding() {
        rolledBack(() -> {
            Preference preference = new Preference();
            preference.setName("fixture_hash");
            preference.setJsonValue("{\"alpha\":1,\"beta\":[true,false]}");
            preferenceRepository.saveAndFlush(preference);

            assertThat(rawColumn("preferences", "value", preference.getId()))
                .isEqualTo("eyJhbHBoYSI6MSwiYmV0YSI6W3RydWUsZmFsc2VdfQ==\n");

            entityManager.clear();
            Preference reloaded = preferenceRepository.findById(preference.getId()).orElseThrow();
            assertThat(reloaded.getJsonValue()).isEqualTo("{\"alpha\":1,\"beta\":[true,false]}");
            assertThat(reloaded.getUser()).isNull();
        });
    }

    @Test
    void persistFillsTimestampsAndUpdateOnlyMovesUpdatedAt() {
        rolledBack(() -> {
            Setting setting = new Setting();
            setting.setName("lifecycle");
            setting.setValue("--- first\n");
            settingRepository.saveAndFlush(setting);

            Instant createdAt = setting.getCreatedAt();
            assertThat(createdAt).isNotNull().isEqualTo(setting.getUpdatedAt());
            assertThat(rawInstant("settings", "created_at", setting.getId())).isEqualTo(createdAt);

            setting.setValue("--- second\n");
            settingRepository.saveAndFlush(setting);

            assertThat(setting.getCreatedAt()).isEqualTo(createdAt);
            assertThat(setting.getUpdatedAt()).isAfter(createdAt);
            assertThat(rawInstant("settings", "updated_at", setting.getId())).isEqualTo(setting.getUpdatedAt());
            assertThat(rawInstant("settings", "created_at", setting.getId())).isEqualTo(createdAt);
        });
    }

    @Test
    void persistKeepsRailsTimestampsCopiedOntoNewRows() {
        rolledBack(() -> {
            Setting setting = new Setting();
            setting.setName("copied");
            setting.setCreatedAt(RAILS_CREATED_AT);
            setting.setUpdatedAt(RAILS_UPDATED_AT);
            settingRepository.saveAndFlush(setting);

            assertThat(rawInstant("settings", "created_at", setting.getId())).isEqualTo(RAILS_CREATED_AT);
            assertThat(rawInstant("settings", "updated_at", setting.getId())).isEqualTo(RAILS_UPDATED_AT);
        });
    }

    private static Tag tag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        return tag;
    }

    private static Tagging tagging(Tag tag, int taggableId, String context) {
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Account");
        tagging.setTaggableId(taggableId);
        tagging.setContext(context);
        return tagging;
    }

    private String rawColumn(String table, String column, Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT " + column + " FROM " + table + " WHERE id = ?", String.class, id
        );
    }

    private Instant rawInstant(String table, String column, Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT " + column + " FROM " + table + " WHERE id = ?", java.sql.Timestamp.class, id
        ).toInstant();
    }

    private void rolledBack(Runnable work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
    }
}
