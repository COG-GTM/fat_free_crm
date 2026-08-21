package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 1 exit criterion: Flyway can baseline the Rails schema and
 * {@code validate} it, and Hibernate's own {@code ddl-auto: validate} agrees
 * with the resulting tables (this test only starts if it did).
 */
@ActiveProfiles("test")
class FlywayBaselineTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void baselineMigrationIsAppliedAndValidates() {
        ValidateResult result = flyway.validateWithResult();
        assertThat(result.validationSuccessful)
            .withFailMessage("Flyway validate failed: %s", result.getAllErrorMessages())
            .isTrue();

        var applied = flyway.info().applied();
        assertThat(applied).isNotEmpty();
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getState()).isIn(MigrationState.SUCCESS, MigrationState.BASELINE);
    }

    @Test
    void railsTablesExistWithRailsColumnNames() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains(
            "users", "groups", "groups_users", "permissions",
            "accounts", "campaigns", "contacts", "leads", "opportunities",
            "tasks", "comments", "emails", "addresses", "fields", "field_groups",
            "settings", "tags", "taggings", "versions", "schema_migrations");

        List<String> userColumns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'users'", String.class);
        assertThat(userColumns).contains(
            "encrypted_password", "password_salt", "sign_in_count",
            "current_sign_in_at", "last_sign_in_at", "suspended_at", "deleted_at");
    }

    @Test
    void railsSchemaMigrationsTableIsPreservedForCoexistence() {
        // Rails must keep working against this database during the strangler window.
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE table_name = 'schema_migrations' AND column_name = 'version'", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
