package com.fatfreecrm.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RailsCsvWriter} to Ruby's {@code CSV.generate} default dialect, as captured with
 * {@code ruby -rcsv} 4.0.2: the empty string is quoted, nil is empty, only the separator, quote and
 * line breaks force quoting, and leading or trailing spaces do not.
 */
class RailsCsvWriterTest {

    @Test
    void emptyRowsProduceAnEmptyDocumentWithoutAHeader() {
        assertThat(RailsCsvWriter.write(List.of("Name", "Email"), List.of())).isEmpty();
    }

    @Test
    void quotesExactlyTheFieldsRubyCsvQuotes() {
        List<Object> row = Arrays.asList(
            "", null, "a,b", "q\"x", "line\nbreak", " lead", "trail ", "x\ry", "#hash");
        String csv = RailsCsvWriter.write(List.of("h"), List.of(row));
        assertThat(csv).isEqualTo(
            "h\n\"\",,\"a,b\",\"q\"\"x\",\"line\nbreak\", lead,trail ,\"x\ry\",#hash\n");
    }

    @Test
    void headerAndRowsUseLineFeedWithoutByteOrderMark() {
        String csv = RailsCsvWriter.write(List.of("Id", "Name"), List.of(
            List.of(1, "Acme"),
            Arrays.asList(2, null)));
        assertThat(csv).isEqualTo("Id,Name\n1,Acme\n2,\n");
        assertThat(csv).doesNotContain("\r\n").doesNotStartWith("\uFEFF");
    }

    @Test
    void cellsAreFormattedWithRubyToS() {
        String csv = RailsCsvWriter.write(List.of("Amount", "Probability", "Created", "Due", "Tags"), List.of(
            Arrays.asList(new BigDecimal("1500.75"), 100.0, LocalDateTime.of(2024, 3, 5, 14, 7, 9),
                LocalDate.of(2024, 3, 6), List.of("vip", "new"))));
        assertThat(csv).isEqualTo(
            "Amount,Probability,Created,Due,Tags\n1500.75,100.0,2024-03-05 14:07:09 UTC,2024-03-06,"
                + "\"[\"\"vip\"\", \"\"new\"\"]\"\n");
    }

    @Test
    void headerCellsFollowTheSameQuotingRules() {
        String csv = RailsCsvWriter.write(List.of("Name, Inc", "Plain"), List.of(List.of("x", "y")));
        assertThat(csv).startsWith("\"Name, Inc\",Plain\n");
    }
}
