package com.fatfreecrm.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins {@link RailsCsvWriter} to the Ruby {@code CSV.generate} dialect used by FatFreeCRM::ExportCSV. */
class RailsCsvWriterTest {

    @Test
    void noRowsProducesAZeroByteBodyWithoutAHeader() {
        assertThat(RailsCsvWriter.write(List.of("id", "name"), List.of())).isEmpty();
    }

    @Test
    void plainValuesAreUnquotedAndLinesEndWithNewline() {
        String csv = RailsCsvWriter.write(List.of("id", "name"), List.of(List.of(1L, "Acme")));
        assertThat(csv).isEqualTo("id,name\n1,Acme\n");
        assertThat(csv).doesNotStartWith("\uFEFF");
    }

    @Test
    void nilIsAnEmptyFieldButAnEmptyStringIsQuoted() {
        String csv = RailsCsvWriter.write(List.of("a", "b", "c"), List.of(Arrays.asList(null, "", null)));
        assertThat(csv).isEqualTo("a,b,c\n,\"\",\n");
    }

    @Test
    void fieldsWithCommasQuotesOrLineBreaksAreQuotedWithDoubledQuotes() {
        List<List<Object>> rows = List.of(List.of(
            "Smith, John", "say \"hi\"", "multi\nline", "carriage\rreturn", "plain"));
        assertThat(RailsCsvWriter.write(List.of("w", "x", "y", "z", "p"), rows))
            .isEqualTo("w,x,y,z,p\n\"Smith, John\",\"say \"\"hi\"\"\",\"multi\nline\",\"carriage\rreturn\",plain\n");
    }

    @Test
    void headerCellsFollowTheSameQuotingRules() {
        assertThat(RailsCsvWriter.write(List.of("tag, list", "name"), List.of(List.of("a", "b"))))
            .isEqualTo("\"tag, list\",name\na,b\n");
    }

    @Test
    void valuesAreRenderedThroughRubyToS() {
        List<List<Object>> rows = List.of(List.of(
            new BigDecimal("1000.50"),
            Timestamp.valueOf(LocalDateTime.of(2025, 3, 4, 5, 6, 7)),
            List.of("x", "y"),
            true));
        assertThat(RailsCsvWriter.write(List.of("amount", "at", "yaml", "flag"), rows))
            .isEqualTo("amount,at,yaml,flag\n1000.5,2025-03-04 05:06:07 UTC,\"[\"\"x\"\", \"\"y\"\"]\",true\n");
    }
}
