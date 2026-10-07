package com.fatfreecrm.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Extend this class to share the PostgreSQL singleton and use the inherited {@code mockMvc}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractPostgresIntegrationTest {

    @ServiceConnection
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("fat_free_crm_test")
        .withUsername("postgres")
        .withPassword("postgres");

    @Autowired
    protected MockMvc mockMvc;

    static {
        POSTGRES.start();
    }
}
