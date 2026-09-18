package com.fatfreecrm.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Registers test-only helpers (they live in {@code src/test} and are not component-scanned). */
@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfig {

    @Bean
    TestDataSeeder testDataSeeder(JdbcTemplate jdbcTemplate) {
        return new TestDataSeeder(jdbcTemplate);
    }
}
