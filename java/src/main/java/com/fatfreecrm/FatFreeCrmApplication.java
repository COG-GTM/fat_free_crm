package com.fatfreecrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Read-only REST API over the Fat Free CRM PostgreSQL database.
 *
 * <p>First strangler slice of the Rails → Spring Boot migration
 * (docs/migration/target-architecture.md §2.7 steps 1-2). Shares the schema with the Rails app,
 * which remains the sole owner of DDL and of all write paths.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded: authentication is header-based
 * (see {@code com.fatfreecrm.security}) and there is no password-backed user store.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class FatFreeCrmApplication {

    public static void main(String[] args) {
        SpringApplication.run(FatFreeCrmApplication.class, args);
    }
}
