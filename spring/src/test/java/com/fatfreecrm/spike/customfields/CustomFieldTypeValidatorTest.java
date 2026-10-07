package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.spike.customfields.CustomFieldTypeValidator.Mode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomFieldTypeValidatorTest {

    private final CustomFieldTypeValidator validator = new CustomFieldTypeValidator();

    private static FieldDefinition f(String name, String as) {
        return new FieldDefinition(name, name, as, false, null, null, null, null, null);
    }

    private static FieldDefinition f(String name, String as, boolean required,
        Integer min, Integer max, List<String> collection, Long id, Long pairId) {
        return new FieldDefinition(name, name, as, required, min, max, collection, id, pairId);
    }

    private static ValidationResult write(List<FieldDefinition> fields, Map<String, Object> input) {
        return new CustomFieldTypeValidator().validate(fields, input, Mode.WRITE);
    }

    private static ValidationResult read(List<FieldDefinition> fields, Map<String, Object> input) {
        return new CustomFieldTypeValidator().validate(fields, input, Mode.READ);
    }

    // --- string family -------------------------------------------------------

    @Test
    void stringAcceptsAndRejects() {
        List<FieldDefinition> fields = List.of(f("cf_s", "string"));
        assertThat(write(fields, Map.of("cf_s", "abc")).normalized().get("cf_s")).isEqualTo("abc");
        assertThat(write(fields, Map.of("cf_s", 12)).errors().get("cf_s"))
            .containsExactly("cf_s is not a string.");
    }

    @Test
    void emailUrlTelTextShareStringRules() {
        for (String as : List.of("email", "url", "tel", "text")) {
            assertThat(write(List.of(f("cf_x", as)), Map.of("cf_x", "anything")).ok()).isTrue();
        }
    }

    @Test
    void minlengthAndMaxlength() {
        List<FieldDefinition> fields =
            List.of(f("cf_s", "string", false, 3, 5, null, null, null));
        assertThat(write(fields, Map.of("cf_s", "ab")).errors().get("cf_s"))
            .containsExactly("cf_s is too short.");
        assertThat(write(fields, Map.of("cf_s", "abcdef")).errors().get("cf_s"))
            .containsExactly("cf_s is too long.");
        assertThat(write(fields, Map.of("cf_s", "abcd")).ok()).isTrue();
    }

    // --- select / radio_buttons ----------------------------------------------

    @Test
    void selectChecksMembershipOnWriteOnly() {
        List<FieldDefinition> fields =
            List.of(f("cf_s", "select", false, null, null, List.of("a", "b"), null, null));
        assertThat(write(fields, Map.of("cf_s", "a")).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_s", "zz")).errors().get("cf_s"))
            .containsExactly("cf_s is not included in the list.");
        // READ: stored non-collection value is kept without error
        assertThat(read(fields, Map.of("cf_s", "zz")).ok()).isTrue();
        assertThat(read(fields, Map.of("cf_s", "zz")).normalized().get("cf_s")).isEqualTo("zz");
    }

    @Test
    void radioButtonsBehaveLikeSelect() {
        List<FieldDefinition> fields =
            List.of(f("cf_r", "radio_buttons", false, null, null, List.of("x"), null, null));
        assertThat(write(fields, Map.of("cf_r", "x")).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_r", "y")).errors()).containsKey("cf_r");
    }

    // --- check_boxes ---------------------------------------------------------

    @Test
    void checkBoxesNormalizeListAndScalars() {
        List<FieldDefinition> fields = List.of(f("cb", "check_boxes"));
        // duplicates removed, blanks dropped, order preserved
        assertThat(write(fields, Map.of("cb", List.of("a", "", "b", "a", " ")))
            .normalized().get("cb")).isEqualTo(List.of("a", "b"));
        // a single String collapses to a one-element list
        assertThat(write(fields, Map.of("cb", "solo")).normalized().get("cb"))
            .isEqualTo(List.of("solo"));
    }

    @Test
    void checkBoxesMembershipOnWrite() {
        List<FieldDefinition> fields =
            List.of(f("cb", "check_boxes", false, null, null, List.of("a", "b"), null, null));
        assertThat(write(fields, Map.of("cb", List.of("a", "b"))).ok()).isTrue();
        assertThat(write(fields, Map.of("cb", List.of("a", "zz"))).errors().get("cb"))
            .containsExactly("cb is not included in the list.");
        // length rules do not apply to check_boxes
        List<FieldDefinition> len =
            List.of(f("cb", "check_boxes", false, 2, null, null, null, null));
        assertThat(write(len, Map.of("cb", List.of("x"))).ok()).isTrue();
    }

    // --- boolean -------------------------------------------------------------

    @Test
    void booleanAcceptsWordsAndRejectsOthers() {
        List<FieldDefinition> fields = List.of(f("cf_b", "boolean"));
        assertThat(write(fields, Map.of("cf_b", true)).normalized().get("cf_b")).isEqualTo(true);
        for (String w : List.of("1", "true", "TRUE", "t")) {
            assertThat(write(fields, Map.of("cf_b", w)).normalized().get("cf_b")).isEqualTo(true);
        }
        for (String w : List.of("0", "false", "f")) {
            assertThat(write(fields, Map.of("cf_b", w)).normalized().get("cf_b")).isEqualTo(false);
        }
        assertThat(write(fields, Map.of("cf_b", "maybe")).errors().get("cf_b"))
            .containsExactly("cf_b is not a boolean.");
    }

    @Test
    void requiredBooleanCannotBeFalse() {
        // Rails quirk: blank?(false) == true, so a required boolean can never be false
        List<FieldDefinition> fields = List.of(f("cf_b", "boolean", true, null, null, null, null, null));
        assertThat(write(fields, Map.of("cf_b", false)).errors().get("cf_b"))
            .containsExactly("cf_b is required.");
        assertThat(write(fields, Map.of("cf_b", true)).ok()).isTrue();
    }

    // --- date / datetime -----------------------------------------------------

    @Test
    void dateNormalizesIsoAndRejectsGarbage() {
        List<FieldDefinition> fields = List.of(f("cf_d", "date"));
        assertThat(write(fields, Map.of("cf_d", "2024-01-31")).normalized().get("cf_d"))
            .isEqualTo("2024-01-31");
        assertThat(write(fields, Map.of("cf_d", "31/01/2024")).errors().get("cf_d"))
            .containsExactly("cf_d is not a valid date.");
    }

    @Test
    void dateReadAcceptsStoredDatetime() {
        // Rails safe transition: a datetime column switched to date leaves "YYYY-MM-DDTHH:MM:SS"
        List<FieldDefinition> fields = List.of(f("cf_d", "date"));
        assertThat(read(fields, Map.of("cf_d", "2024-01-31T10:00:00")).normalized().get("cf_d"))
            .isEqualTo("2024-01-31");
        // WRITE rejects it
        assertThat(write(fields, Map.of("cf_d", "2024-01-31T10:00:00")).errors())
            .containsKey("cf_d");
    }

    @Test
    void datetimeNormalizesOffsetsToUtc() {
        List<FieldDefinition> fields = List.of(f("cf_t", "datetime"));
        // offset input converted to UTC
        assertThat(write(fields, Map.of("cf_t", "2024-01-31T12:00:00+02:00"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T10:00:00Z");
        // no offset treated as UTC (Rails timestamp without time zone)
        assertThat(write(fields, Map.of("cf_t", "2024-01-31T10:00:00"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T10:00:00Z");
        // READ accepts a bare date as midnight UTC (former date column)
        assertThat(read(fields, Map.of("cf_t", "2024-01-31"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T00:00:00Z");
        assertThat(write(fields, Map.of("cf_t", "not-a-time")).errors().get("cf_t"))
            .containsExactly("cf_t is not a valid datetime.");
    }

    // --- numeric -------------------------------------------------------------

    @Test
    void decimalRoundsHalfUpToScale2AndBoundsRange() {
        List<FieldDefinition> fields = List.of(f("cf_m", "decimal"));
        assertThat(write(fields, Map.of("cf_m", "12.345")).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("12.35"));
        assertThat(write(fields, Map.of("cf_m", 12.344)).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("12.34"));
        assertThat(write(fields, Map.of("cf_m", "12345678901234.5")).errors().get("cf_m"))
            .containsExactly("cf_m is out of range."); // 14 integer digits > 13
        assertThat(write(fields, Map.of("cf_m", "abc")).errors()).containsKey("cf_m");
    }

    @Test
    void integerIsInt32AndRejectsFractional() {
        List<FieldDefinition> fields = List.of(f("cf_i", "integer"));
        assertThat(write(fields, Map.of("cf_i", "42")).normalized().get("cf_i")).isEqualTo(42);
        assertThat(write(fields, Map.of("cf_i", 7)).normalized().get("cf_i")).isEqualTo(7);
        assertThat(write(fields, Map.of("cf_i", 2147483648L)).errors().get("cf_i"))
            .containsExactly("cf_i is out of range.");
        assertThat(write(fields, Map.of("cf_i", "3.7")).errors().get("cf_i"))
            .containsExactly("cf_i must be an integer.");
    }

    @Test
    void floatRejectsNaNAndInfinity() {
        List<FieldDefinition> fields = List.of(f("cf_f", "float"));
        assertThat(write(fields, Map.of("cf_f", "1.5")).normalized().get("cf_f")).isEqualTo(1.5d);
        assertThat(write(fields, Map.of("cf_f", "NaN")).errors().get("cf_f"))
            .containsExactly("cf_f is not a number.");
        assertThat(write(fields, Map.of("cf_f", "xyz")).errors()).containsKey("cf_f");
    }

    // --- required / unknown keys ---------------------------------------------

    @Test
    void requiredBlankSemantics() {
        List<FieldDefinition> fields = List.of(f("cf_s", "string", true, null, null, null, null, null));
        for (Object blank : new Object[]{null, "", "   "}) {
            assertThat(write(fields, java.util.Collections.singletonMap("cf_s", blank))
                .errors().get("cf_s")).containsExactly("cf_s is required.");
        }
        // absent key on WRITE behaves as null
        assertThat(write(fields, Map.of()).errors().get("cf_s"))
            .containsExactly("cf_s is required.");
        // READ never raises required errors
        assertThat(read(fields, Map.of()).ok()).isTrue();
        assertThat(read(fields, Map.of("cf_s", "")).ok()).isTrue();
    }

    @Test
    void unknownKeyWriteErrorsReadDrops() {
        List<FieldDefinition> fields = List.of(f("cf_s", "string"));
        assertThat(write(fields, Map.of("cf_unknown", "x")).errors().get("cf_unknown"))
            .containsExactly("cf_unknown is not a defined custom field.");
        ValidationResult r = read(fields, Map.of("cf_unknown", "x", "cf_s", "ok"));
        assertThat(r.ok()).isTrue();
        assertThat(r.normalized()).doesNotContainKey("cf_unknown").containsEntry("cf_s", "ok");
    }

    @Test
    void readKeepsRawValueOnConversionError() {
        List<FieldDefinition> fields = List.of(f("cf_d", "date"));
        ValidationResult r = read(fields, Map.of("cf_d", 12345));
        assertThat(r.ok()).isTrue();
        assertThat(r.normalized().get("cf_d")).isEqualTo(12345);
    }

    // --- pairs ---------------------------------------------------------------

    private static List<FieldDefinition> datePair() {
        return List.of(
            f("cf_from", "date_pair", true, null, null, null, 1L, null),
            f("cf_to", "date_pair", false, null, null, null, 2L, 1L));
    }

    @Test
    void datePairEndBeforeStartErrors() {
        assertThat(write(datePair(),
            Map.of("cf_from", "2024-02-01", "cf_to", "2024-01-01")).errors().get("cf_to"))
            .containsExactly("cf_from cannot end before it begins.");
    }

    @Test
    void datePairEqualStartEndIsOk() {
        assertThat(write(datePair(),
            Map.of("cf_from", "2024-02-01", "cf_to", "2024-02-01")).ok()).isTrue();
    }

    @Test
    void pairCopiesRequiredFromStart() {
        // end half required flag comes from the start half
        assertThat(write(datePair(), Map.of("cf_from", "2024-02-01")).errors())
            .containsKey("cf_to");
    }

    @Test
    void datetimePairEndBeforeStartErrors() {
        List<FieldDefinition> fields = List.of(
            f("cf_start", "datetime_pair", false, null, null, null, 1L, null),
            f("cf_end", "datetime_pair", false, null, null, null, 2L, 1L));
        assertThat(write(fields, Map.of(
            "cf_start", "2024-01-31T10:00:00",
            "cf_end", "2024-01-31T09:00:00")).errors().get("cf_end"))
            .containsExactly("cf_start cannot end before it begins.");
        assertThat(write(fields, Map.of(
            "cf_start", "2024-01-31T10:00:00",
            "cf_end", "2024-01-31T11:00:00")).ok()).isTrue();
    }

    @Test
    void readModeNeverRunsPairCheck() {
        // end < start stored data: no error in READ (normalization only)
        assertThat(read(datePair(),
            Map.of("cf_from", "2024-02-01", "cf_to", "2024-01-01")).ok()).isTrue();
        // a raw unparseable half is kept as-is and cannot blow up the pair check
        ValidationResult r = read(datePair(),
            Map.of("cf_from", "2024-02-01", "cf_to", 123));
        assertThat(r.ok()).isTrue();
        assertThat(r.normalized().get("cf_to")).isEqualTo(123);
    }

    @Test
    void datetimePreservesPostgresMicrosecondPrecision() {
        List<FieldDefinition> fields = List.of(f("cf_t", "datetime"));
        // full Postgres microsecond precision round-trips
        assertThat(write(fields, Map.of("cf_t", "2024-01-31T10:00:00.123456Z"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T10:00:00.123456Z");
        // offset conversion keeps the fraction
        assertThat(write(fields, Map.of("cf_t", "2024-01-31T12:00:00.5+02:00"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T10:00:00.5Z");
        // trigger form (no offset, fractional seconds) gains Z in READ
        assertThat(read(fields, Map.of("cf_t", "2024-01-31T10:00:00.123456"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T10:00:00.123456Z");
        // whole seconds print no fraction
        assertThat(write(fields, Map.of("cf_t", "2024-01-31T10:00:00Z"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T10:00:00Z");
    }
}
