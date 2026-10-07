package com.fatfreecrm.support;

import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 16 loaded with the Rails-generated schema ({@code db/rails/rails_schema.sql}) and the Rails-seeded
 * data set ({@code db/rails/rails_seed_data.sql}, produced by {@code spring/scripts/generate-seed-fixture.sh}).
 * Flyway baselines the database and Hibernate validates the JPA model against it on context start.
 */
@SpringBootTest
public abstract class AbstractRailsSeededIntegrationTest {

    protected static final String DATABASE = "fat_free_crm_seeded";

    @ServiceConnection
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName(DATABASE)
        .withUsername("postgres")
        .withPassword("postgres");

    static {
        POSTGRES.start();
        loadSql("db/rails/rails_schema.sql");
        loadSql("db/rails/rails_seed_data.sql");
    }

    private static void loadSql(String classpathResource) {
        var target = "/tmp/" + classpathResource.substring(classpathResource.lastIndexOf('/') + 1);
        try {
            POSTGRES.copyFileToContainer(MountableFile.forClasspathResource(classpathResource), target);
            var result = POSTGRES.execInContainer(
                "psql", "-U", "postgres", "-d", DATABASE, "-v", "ON_ERROR_STOP=1", "-q", "-f", target
            );
            if (result.getExitCode() != 0) {
                fail("Loading %s failed: %s", classpathResource, result.getStderr());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExceptionInInitializerError(exception);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
