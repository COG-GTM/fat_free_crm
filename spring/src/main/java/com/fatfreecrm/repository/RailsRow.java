package com.fatfreecrm.repository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RailsRow {

    private final Long id;
    private final Map<String, Object> columns;
    private final Map<String, String> typeNames;

    public RailsRow(Long id, Map<String, Object> columns, Map<String, String> typeNames) {
        this.id = id;
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        this.typeNames = Collections.unmodifiableMap(new LinkedHashMap<>(typeNames));
    }

    public Long id() {
        return id;
    }

    public Map<String, Object> columns() {
        return columns;
    }

    public Map<String, String> typeNames() {
        return typeNames;
    }
}
