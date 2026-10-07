package com.fatfreecrm.customfields;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record CustomFieldsBackfillReport(Map<String, TableReport> tables) {

    public CustomFieldsBackfillReport {
        tables = new LinkedHashMap<>(tables);
    }

    @Override
    public Map<String, TableReport> tables() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public boolean ok() {
        return tables.values().stream().allMatch(TableReport::ok);
    }

    public record ColumnCounts(long columnNonNull, long jsonbKeyNonNull) {

        public boolean ok() {
            return columnNonNull == jsonbKeyNonNull;
        }
    }

    public record TableReport(
        long rows,
        long rowsBackfilled,
        long drift,
        long markersRemaining,
        Map<String, ColumnCounts> columns
    ) {
        public TableReport {
            columns = new LinkedHashMap<>(columns);
        }

        @Override
        public Map<String, ColumnCounts> columns() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        }

        public boolean ok() {
            return drift == 0 && markersRemaining == 0
                && columns.values().stream().allMatch(ColumnCounts::ok);
        }
    }
}
