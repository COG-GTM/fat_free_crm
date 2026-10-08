package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ActivityVisibilityRepositoryTest {

    @Test
    void refusesTablesThatAreNotAccessControlledBeforeTouchingTheDatabase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ActivityVisibilityRepository repository = new ActivityVisibilityRepository(jdbcTemplate);

        for (String table : new String[] {"users", "tasks", "comments", "accounts; drop table users", ""}) {
            assertThatThrownBy(() -> repository.findAccessFields(table, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported access-controlled table");
        }
        verifyNoInteractions(jdbcTemplate);
    }
}
