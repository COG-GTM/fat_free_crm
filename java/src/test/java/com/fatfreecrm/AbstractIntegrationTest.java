package com.fatfreecrm;

import com.fatfreecrm.support.TestDataSeeder;
import com.fatfreecrm.support.TestSupportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: boots the full application on a random port against a
 * single shared PostgreSQL 16 Testcontainer. The container is started once per JVM (singleton
 * pattern, torn down by Testcontainers' Ryuk sidecar) rather than with {@code @Container}, so
 * the cached Spring context never outlives its database. The schema is created from
 * {@code src/test/resources/schema-test.sql} via {@code spring.sql.init} in the {@code test}
 * profile, and all rows are wiped before each test via {@link TestDataSeeder#truncateAll()}.
 *
 * <p>Requires a Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSupportConfig.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("fat_free_crm_test")
                    .withUsername("ffcrm")
                    .withPassword("ffcrm");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestDataSeeder seeder;

    @BeforeEach
    void resetDatabase() {
        seeder.truncateAll();
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
