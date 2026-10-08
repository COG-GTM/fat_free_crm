package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomFieldReportsTest {

    @Test
    void consistencyReportIsOkOnlyWhenNoTableDrifted() {
        CustomFieldConsistencyReport.TableReport clean =
            new CustomFieldConsistencyReport.TableReport(10, 0, List.of(), Map.of());
        CustomFieldConsistencyReport.TableReport drifted =
            new CustomFieldConsistencyReport.TableReport(10, 2, List.of(3L, 7L), Map.of("cf_x", 2L));

        assertThat(clean.ok()).isTrue();
        assertThat(drifted.ok()).isFalse();
        assertThat(new CustomFieldConsistencyReport(Map.of()).ok()).isTrue();
        assertThat(new CustomFieldConsistencyReport(Map.of("accounts", clean)).ok()).isTrue();
        assertThat(new CustomFieldConsistencyReport(
            Map.of("accounts", clean, "contacts", drifted)).ok()).isFalse();
    }

    @Test
    void backfillReportIsOkOnlyWithoutDriftMarkersOrColumnCountMismatches() {
        CustomFieldsBackfillReport.ColumnCounts balanced = new CustomFieldsBackfillReport.ColumnCounts(5, 5);
        CustomFieldsBackfillReport.ColumnCounts unbalanced = new CustomFieldsBackfillReport.ColumnCounts(5, 4);
        assertThat(balanced.ok()).isTrue();
        assertThat(unbalanced.ok()).isFalse();

        assertThat(new CustomFieldsBackfillReport.TableReport(5, 5, 0, 0, Map.of("cf_a", balanced)).ok())
            .isTrue();
        assertThat(new CustomFieldsBackfillReport.TableReport(5, 5, 1, 0, Map.of("cf_a", balanced)).ok())
            .isFalse();
        assertThat(new CustomFieldsBackfillReport.TableReport(5, 5, 0, 1, Map.of("cf_a", balanced)).ok())
            .isFalse();
        assertThat(new CustomFieldsBackfillReport.TableReport(5, 5, 0, 0, Map.of("cf_a", unbalanced)).ok())
            .isFalse();

        CustomFieldsBackfillReport.TableReport okTable =
            new CustomFieldsBackfillReport.TableReport(1, 1, 0, 0, Map.of());
        CustomFieldsBackfillReport.TableReport badTable =
            new CustomFieldsBackfillReport.TableReport(1, 1, 1, 0, Map.of());
        assertThat(new CustomFieldsBackfillReport(Map.of()).ok()).isTrue();
        assertThat(new CustomFieldsBackfillReport(Map.of("accounts", okTable, "tasks", badTable)).ok()).isFalse();
    }

    @Test
    void reportsAreDefensivelyCopiedAndReadOnly() {
        List<Long> ids = new ArrayList<>(List.of(1L));
        Map<String, Long> drift = new LinkedHashMap<>(Map.of("cf_x", 1L));
        CustomFieldConsistencyReport.TableReport table =
            new CustomFieldConsistencyReport.TableReport(1, 1, ids, drift);
        Map<String, CustomFieldConsistencyReport.TableReport> tables = new LinkedHashMap<>(Map.of("accounts", table));
        CustomFieldConsistencyReport report = new CustomFieldConsistencyReport(tables);

        ids.add(2L);
        drift.put("cf_y", 1L);
        tables.clear();

        assertThat(report.tables()).containsOnlyKeys("accounts");
        assertThat(table.driftedRowIds()).containsExactly(1L);
        assertThat(table.keyDriftCounts()).containsOnlyKeys("cf_x");
        assertThatThrownBy(() -> report.tables().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> table.driftedRowIds().add(9L)).isInstanceOf(UnsupportedOperationException.class);

        Map<String, CustomFieldsBackfillReport.ColumnCounts> columns = new LinkedHashMap<>();
        columns.put("cf_a", new CustomFieldsBackfillReport.ColumnCounts(1, 1));
        CustomFieldsBackfillReport.TableReport backfillTable =
            new CustomFieldsBackfillReport.TableReport(1, 1, 0, 0, columns);
        columns.clear();
        assertThat(backfillTable.columns()).containsOnlyKeys("cf_a");
        assertThatThrownBy(() -> backfillTable.columns().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
