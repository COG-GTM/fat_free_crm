package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RailsRowTest {

    @Test
    void preservesColumnOrderAndIsImmutable() {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("id", 5L);
        columns.put("name", "Acme");
        columns.put("website", null);
        Map<String, String> types = new LinkedHashMap<>();
        types.put("id", "int8");
        types.put("name", "varchar");
        types.put("website", "varchar");

        RailsRow row = new RailsRow(5L, columns, types);
        columns.put("extra", 1);
        types.remove("id");

        assertThat(row.id()).isEqualTo(5L);
        assertThat(row.columns().keySet()).containsExactly("id", "name", "website");
        assertThat(row.columns()).containsEntry("website", null);
        assertThat(row.typeNames()).containsEntry("id", "int8");
        assertThatThrownBy(() -> row.columns().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> row.typeNames().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allowsRowsWithoutAnIdColumn() {
        RailsRow row = new RailsRow(null, Map.of("name", "x"), Map.of("name", "varchar"));

        assertThat(row.id()).isNull();
        assertThat(row.columns()).containsOnlyKeys("name");
    }
}
