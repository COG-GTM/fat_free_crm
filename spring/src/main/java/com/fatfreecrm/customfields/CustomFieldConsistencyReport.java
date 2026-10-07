package com.fatfreecrm.customfields;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CustomFieldConsistencyReport(Map<String, TableReport> tables) {

    public CustomFieldConsistencyReport {
        tables = new LinkedHashMap<>(tables);
    }

    @Override
    public Map<String, TableReport> tables() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public boolean ok() {
        return tables.values().stream().allMatch(TableReport::ok);
    }

    public record TableReport(
        long rowsChecked,
        long driftedRows,
        List<Long> driftedRowIds,
        Map<String, Long> keyDriftCounts
    ) {
        public TableReport {
            driftedRowIds = new ArrayList<>(driftedRowIds);
            keyDriftCounts = new LinkedHashMap<>(keyDriftCounts);
        }

        @Override
        public List<Long> driftedRowIds() {
            return Collections.unmodifiableList(new ArrayList<>(driftedRowIds));
        }

        @Override
        public Map<String, Long> keyDriftCounts() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(keyDriftCounts));
        }

        public boolean ok() {
            return driftedRows == 0;
        }
    }
}
