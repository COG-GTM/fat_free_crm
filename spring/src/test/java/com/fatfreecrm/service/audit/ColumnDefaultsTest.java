package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class ColumnDefaultsTest {

    @Test
    void parsesLiteralColumnDefaultsIntoAttributeTypes() {
        assertThat(ColumnDefaults.parseDefault("boolean", "true")).isEqualTo(true);
        assertThat(ColumnDefaults.parseDefault("integer", "0")).isEqualTo(0);
        assertThat(ColumnDefaults.parseDefault("character varying", "''::character varying")).isEqualTo("");
        assertThat(ColumnDefaults.parseDefault("numeric", "1.25::numeric"))
            .isEqualTo(new BigDecimal("1.25"));
        assertThat(ColumnDefaults.parseDefault("date", "'2025-01-02'::date"))
            .isEqualTo(LocalDate.parse("2025-01-02"));
    }

    @Test
    void treatsNullAndExpressionDefaultsAsNull() {
        assertThat(ColumnDefaults.parseDefault("integer", null)).isNull();
        assertThat(ColumnDefaults.parseDefault("bigint", "nextval('tasks_id_seq'::regclass)")).isNull();
        assertThat(ColumnDefaults.parseDefault("timestamp with time zone", "now()")).isNull();
    }

    @Test
    void cachesColumnDefaultsPerTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ColumnDefaults defaults = new ColumnDefaults(jdbcTemplate);

        assertThat(defaults.defaults("comments")).isEmpty();
        assertThat(defaults.defaults("comments")).isEmpty();

        verify(jdbcTemplate, times(1)).query(anyString(), any(RowCallbackHandler.class), eq("comments"));
    }
}
