package com.fatfreecrm.support;

import java.io.IOException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Shares one PostgreSQL container pre-loaded with the Rails-generated schema and entity fixture
 * ({@code db/rails/rails_schema.sql} and {@code db/rails/entity_fixture.sql}) across test classes.
 */
public final class RailsEntityFixturePostgres {

    private static final String DATABASE = "fat_free_crm_entity_fixture";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName(DATABASE)
        .withUsername("postgres")
        .withPassword("postgres");

    private RailsEntityFixturePostgres() {
    }

    public static synchronized PostgreSQLContainer<?> started() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
            try {
                load("db/rails/rails_schema.sql");
                load("db/rails/entity_fixture.sql");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExceptionInInitializerError(exception);
            } catch (IOException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }
        return CONTAINER;
    }

    private static void load(String resource) throws IOException, InterruptedException {
        String path = "/tmp/" + resource.substring(resource.lastIndexOf('/') + 1);
        CONTAINER.copyFileToContainer(MountableFile.forClasspathResource(resource), path);
        var load = CONTAINER.execInContainer(
            "psql", "-U", "postgres", "-d", DATABASE, "-v", "ON_ERROR_STOP=1", "-f", path
        );
        if (load.getExitCode() != 0) {
            throw new IOException("Loading " + path + " failed: " + load.getStderr());
        }
    }
}
