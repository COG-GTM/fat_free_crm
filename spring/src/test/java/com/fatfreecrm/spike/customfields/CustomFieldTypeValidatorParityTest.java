package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.spike.customfields.CustomFieldTypeValidator.Mode;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link CustomFieldTypeValidator} to the Rails behaviour it ports
 * ({@code CustomField#custom_validator}, {@code CustomFieldDatePair#custom_validator},
 * PostgreSQL column types from {@code Field::BASE_FIELD_TYPES}) on the edges the
 * main test does not reach: blank semantics beyond strings, error ordering, code
 * point lengths, numeric boundaries and pair comparison after normalisation.
 */
class CustomFieldTypeValidatorParityTest {

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

    // --- required: Rails blank? on non-string values ---------------------------

    @Test
    void requiredCheckBoxesRejectsEmptyListAndBlankOnlyItems() {
        // Rails: [].blank? is true; after normalisation ["", " "] is [] too
        List<FieldDefinition> fields =
            List.of(f("cb", "check_boxes", true, null, null, null, null, null));
        assertThat(write(fields, Map.of("cb", List.of())).errors().get("cb"))
            .containsExactly("cb is required.");
        assertThat(write(fields, Map.of("cb", List.of("", " "))).errors().get("cb"))
            .containsExactly("cb is required.");
        assertThat(write(fields, Map.of("cb", List.of("a"))).ok()).isTrue();
    }

    @Test
    void requiredNumericZeroIsNotBlank() {
        // Rails: 0.blank? and 0.0.blank? are false, only nil/""/false are blank
        assertThat(write(List.of(f("i", "integer", true, null, null, null, null, null)),
            Map.of("i", 0)).ok()).isTrue();
        assertThat(write(List.of(f("d", "decimal", true, null, null, null, null, null)),
            Map.of("d", "0")).ok()).isTrue();
        assertThat(write(List.of(f("fl", "float", true, null, null, null, null, null)),
            Map.of("fl", 0.0d)).ok()).isTrue();
    }

    @Test
    void requiredAndLengthErrorsAccumulateInRailsOrder() {
        // CustomField#custom_validator adds required, then minlength, then maxlength
        List<FieldDefinition> fields =
            List.of(f("cf_s", "string", true, 3, null, null, null, null));
        assertThat(write(fields, Map.of("cf_s", "")).errors().get("cf_s"))
            .containsExactly("cf_s is required.", "cf_s is too short.");
    }

    @Test
    void conversionFailureOnRequiredFieldReportsTypeErrorThenRequired() {
        // an uncoercible value normalises to null, so the required check fires too;
        // the type error is reported first
        List<FieldDefinition> fields =
            List.of(f("cf_i", "integer", true, null, null, null, null, null));
        assertThat(write(fields, Map.of("cf_i", "abc")).errors().get("cf_i"))
            .containsExactly("cf_i is not a number.", "cf_i is required.");
        // and an optional field reports only the type error
        assertThat(write(List.of(f("cf_i", "integer")), Map.of("cf_i", "abc")).errors().get("cf_i"))
            .containsExactly("cf_i is not a number.");
    }

    // --- lengths: Rails String#length counts code points -------------------------

    @Test
    void lengthRulesCountCodePointsNotUtf16Units() {
        String threeEmoji = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"; // 3 code points, 6 chars
        List<FieldDefinition> fields =
            List.of(f("cf_s", "string", false, 3, 3, null, null, null));
        assertThat(write(fields, Map.of("cf_s", threeEmoji)).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_s", threeEmoji + "x")).errors().get("cf_s"))
            .containsExactly("cf_s is too long.");
        assertThat(write(fields, Map.of("cf_s", "\uD83D\uDE00\uD83D\uDE00")).errors().get("cf_s"))
            .containsExactly("cf_s is too short.");
    }

    @Test
    void zeroLengthLimitsAreIgnored() {
        // Rails: minlength.to_i > 0 / maxlength.to_i > 0 gate the checks
        List<FieldDefinition> fields =
            List.of(f("cf_s", "string", false, 0, 0, null, null, null));
        assertThat(write(fields, Map.of("cf_s", "")).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_s", "a very long value indeed")).ok()).isTrue();
    }

    @Test
    void lengthRulesApplyToTextSelectAndRadioStrings() {
        List<FieldDefinition> fields = List.of(
            f("cf_t", "text", false, null, 3, null, null, null),
            f("cf_sel", "select", false, 4, null, List.of("ab", "abcd"), null, null));
        ValidationResult r = write(fields, Map.of("cf_t", "abcd", "cf_sel", "ab"));
        assertThat(r.errors().get("cf_t")).containsExactly("cf_t is too long.");
        assertThat(r.errors().get("cf_sel")).containsExactly("cf_sel is too short.");
    }

    // --- select / check_boxes collection edge cases ----------------------------

    @Test
    void emptyCollectionDisablesMembershipCheck() {
        // Rails has no model-level inclusion check at all; the port only enforces
        // membership when fields.collection is non-empty
        assertThat(write(List.of(f("cf_s", "select", false, null, null, List.of(), null, null)),
            Map.of("cf_s", "anything")).ok()).isTrue();
        assertThat(write(List.of(f("cb", "check_boxes", false, null, null, List.of(), null, null)),
            Map.of("cb", List.of("x", "y"))).ok()).isTrue();
    }

    @Test
    void checkBoxesRejectNonListNonStringAndStringifyScalars() {
        List<FieldDefinition> fields = List.of(f("cb", "check_boxes"));
        assertThat(write(fields, Map.of("cb", 42)).errors().get("cb"))
            .containsExactly("cb is not a list.");
        // Psych fixtures stringify non-string scalars; the validator does the same
        assertThat(write(fields, Map.of("cb", List.of(1, true, "x"))).normalized().get("cb"))
            .isEqualTo(List.of("1", "true", "x"));
        // nulls inside the list are dropped like blanks
        assertThat(write(fields, Map.of("cb", java.util.Arrays.asList("a", null, "b")))
            .normalized().get("cb")).isEqualTo(List.of("a", "b"));
    }

    // --- numeric boundaries: PostgreSQL integer / numeric(15,2) ----------------

    @Test
    void integerAcceptsInt32BoundsAndRejectsJustOutside() {
        List<FieldDefinition> fields = List.of(f("cf_i", "integer"));
        assertThat(write(fields, Map.of("cf_i", 2147483647L)).normalized().get("cf_i"))
            .isEqualTo(Integer.MAX_VALUE);
        assertThat(write(fields, Map.of("cf_i", "-2147483648")).normalized().get("cf_i"))
            .isEqualTo(Integer.MIN_VALUE);
        assertThat(write(fields, Map.of("cf_i", -2147483649L)).errors().get("cf_i"))
            .containsExactly("cf_i is out of range.");
        // "3.0" is integral and accepted; "3.5" is rejected by the main test
        assertThat(write(fields, Map.of("cf_i", "3.0")).normalized().get("cf_i")).isEqualTo(3);
        assertThat(write(fields, Map.of("cf_i", true)).errors().get("cf_i"))
            .containsExactly("cf_i is not a number.");
    }

    @Test
    void decimalBoundsAreSymmetricAndRoundingIsHalfAwayFromZero() {
        List<FieldDefinition> fields = List.of(f("cf_m", "decimal"));
        // 13 integer digits is the numeric(15,2) maximum
        assertThat(write(fields, Map.of("cf_m", "9999999999999.99")).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("9999999999999.99"));
        assertThat(write(fields, Map.of("cf_m", "-9999999999999.99")).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_m", "-12345678901234.5")).errors().get("cf_m"))
            .containsExactly("cf_m is out of range.");
        // a value that rounds up into 14 digits is out of range too
        assertThat(write(fields, Map.of("cf_m", "9999999999999.999")).errors().get("cf_m"))
            .containsExactly("cf_m is out of range.");
        // PostgreSQL numeric rounds half away from zero
        assertThat(write(fields, Map.of("cf_m", "-0.005")).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("-0.01"));
        assertThat(write(fields, Map.of("cf_m", 7)).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void floatRejectsNonNumericTypesAndInfinity() {
        List<FieldDefinition> fields = List.of(f("cf_f", "float"));
        assertThat(write(fields, Map.of("cf_f", "Infinity")).errors().get("cf_f"))
            .containsExactly("cf_f is not a number.");
        assertThat(write(fields, Map.of("cf_f", true)).errors().get("cf_f"))
            .containsExactly("cf_f is not a number.");
        assertThat(write(fields, Map.of("cf_f", 2)).normalized().get("cf_f")).isEqualTo(2.0d);
    }

    // --- READ mode keeps raw values for every numeric/boolean failure ----------

    @Test
    void readKeepsRawValueForNumericAndBooleanConversionFailures() {
        List<FieldDefinition> fields = List.of(
            f("cf_i", "integer"), f("cf_m", "decimal"), f("cf_f", "float"), f("cf_b", "boolean"));
        ValidationResult r = read(fields,
            Map.of("cf_i", "abc", "cf_m", "x", "cf_f", "NaN", "cf_b", "maybe"));
        assertThat(r.ok()).isTrue();
        assertThat(r.normalized())
            .containsEntry("cf_i", "abc")
            .containsEntry("cf_m", "x")
            .containsEntry("cf_f", "NaN")
            .containsEntry("cf_b", "maybe");
    }

    @Test
    void readStillNormalisesCoercibleStoredValues() {
        List<FieldDefinition> fields = List.of(f("cf_i", "integer"), f("cf_b", "boolean"));
        ValidationResult r = read(fields, Map.of("cf_i", "42", "cf_b", "t"));
        assertThat(r.normalized()).containsEntry("cf_i", 42).containsEntry("cf_b", true);
    }

    @Test
    void readOfExplicitNullKeepsKeyWithNullValue() {
        List<FieldDefinition> fields = List.of(f("cf_s", "string"));
        ValidationResult r = read(fields, Collections.singletonMap("cf_s", null));
        assertThat(r.ok()).isTrue();
        assertThat(r.normalized()).containsKey("cf_s");
        assertThat(r.normalized().get("cf_s")).isNull();
    }

    // --- datetime / date edges -------------------------------------------------

    @Test
    void datetimeIsNormalisedToMicrosecondsAndDateOnlyIsRejectedOnWrite() {
        List<FieldDefinition> fields = List.of(f("cf_t", "datetime"));
        // nanoseconds are truncated to PostgreSQL microsecond precision
        assertThat(write(fields, Map.of("cf_t", "2024-01-31T10:00:00.123456789Z"))
            .normalized().get("cf_t")).isEqualTo("2024-01-31T10:00:00.123456Z");
        // a bare date is only accepted in READ (former date column)
        assertThat(write(fields, Map.of("cf_t", "2024-01-31")).errors().get("cf_t"))
            .containsExactly("cf_t is not a valid datetime.");
        assertThat(write(fields, Map.of("cf_t", 1706695200)).errors().get("cf_t"))
            .containsExactly("cf_t is not a valid datetime.");
    }

    @Test
    void dateReadAcceptsOffsetDatetimeAndRejectsNonIsoOnBothModes() {
        List<FieldDefinition> fields = List.of(f("cf_d", "date"));
        assertThat(read(fields, Map.of("cf_d", "2024-01-31T23:30:00+02:00")).normalized().get("cf_d"))
            .isEqualTo("2024-01-31");
        assertThat(write(fields, Map.of("cf_d", "2024-02-30")).errors().get("cf_d"))
            .containsExactly("cf_d is not a valid date.");
        // READ: unparseable stays raw rather than failing
        assertThat(read(fields, Map.of("cf_d", "31/01/2024")).normalized().get("cf_d"))
            .isEqualTo("31/01/2024");
    }

    // --- pairs -----------------------------------------------------------------

    private static List<FieldDefinition> datetimePair(boolean required) {
        return List.of(
            f("cf_start", "datetime_pair", required, null, null, null, 10L, null),
            f("cf_end", "datetime_pair", false, null, null, null, 11L, 10L));
    }

    @Test
    void datetimePairComparesInstantsAfterUtcNormalisation() {
        // 12:00+02:00 is 10:00Z, so an end of 09:00Z is before it
        assertThat(write(datetimePair(false), Map.of(
            "cf_start", "2024-01-31T12:00:00+02:00",
            "cf_end", "2024-01-31T09:00:00Z")).errors().get("cf_end"))
            .containsExactly("cf_start cannot end before it begins.");
        // 12:00+02:00 is 10:00Z, so an end of 11:00Z is after it
        assertThat(write(datetimePair(false), Map.of(
            "cf_start", "2024-01-31T12:00:00+02:00",
            "cf_end", "2024-01-31T11:00:00Z")).ok()).isTrue();
    }

    @Test
    void datePairComparesChronologicallyAcrossYearBoundary() {
        List<FieldDefinition> fields = List.of(
            f("cf_from", "date_pair", false, null, null, null, 1L, null),
            f("cf_to", "date_pair", false, null, null, null, 2L, 1L));
        assertThat(write(fields, Map.of("cf_from", "2023-12-31", "cf_to", "2024-01-01")).ok())
            .isTrue();
        assertThat(write(fields, Map.of("cf_from", "2024-01-01", "cf_to", "2023-12-31"))
            .errors().get("cf_to")).containsExactly("cf_from cannot end before it begins.");
    }

    @Test
    void pairCheckSkippedWhenEitherHalfIsMissingOrInvalid() {
        List<FieldDefinition> fields = List.of(
            f("cf_from", "date_pair", false, null, null, null, 1L, null),
            f("cf_to", "date_pair", false, null, null, null, 2L, 1L));
        // Rails: from.present? && to.present? gates the comparison
        assertThat(write(fields, Map.of("cf_to", "2024-01-01")).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_from", "2024-01-01")).ok()).isTrue();
        // an unparseable end half reports only its own type error
        assertThat(write(fields, Map.of("cf_from", "2024-02-01", "cf_to", "garbage"))
            .errors()).containsOnlyKeys("cf_to");
        assertThat(write(fields, Map.of("cf_from", "2024-02-01", "cf_to", "garbage"))
            .errors().get("cf_to")).containsExactly("cf_to is not a valid date.");
    }

    @Test
    void pairEndHalfInheritsRequiredFromStartEvenWhenOwnFlagIsFalse() {
        // CustomFieldPair.create_pair copies required from the start half
        ValidationResult r = write(datetimePair(true), Map.of());
        assertThat(r.errors().get("cf_start")).containsExactly("cf_start is required.");
        assertThat(r.errors().get("cf_end")).containsExactly("cf_start is required.".replace(
            "cf_start", "cf_end"));
    }

    @Test
    void pairWithUnknownStartIdFallsBackToOwnRequiredFlag() {
        List<FieldDefinition> fields =
            List.of(f("cf_end", "date_pair", false, null, null, null, 2L, 99L));
        assertThat(write(fields, Map.of()).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_end", "2024-01-01")).normalized().get("cf_end"))
            .isEqualTo("2024-01-01");
    }

    // --- unknown fields and output shape ---------------------------------------

    @Test
    void writeNormalisesKnownKeysEvenWhenAnUnknownKeyErrors() {
        List<FieldDefinition> fields = List.of(f("cf_s", "string"));
        ValidationResult r = write(fields, Map.of("cf_s", "ok", "cf_nope", 1));
        assertThat(r.ok()).isFalse();
        assertThat(r.normalized()).containsOnlyKeys("cf_s").containsEntry("cf_s", "ok");
        assertThat(r.errors()).containsOnlyKeys("cf_nope");
    }

    @Test
    void writeOutputContainsEveryDefinedFieldInDefinitionOrder() {
        List<FieldDefinition> fields = List.of(f("cf_b", "string"), f("cf_a", "integer"));
        ValidationResult r = write(fields, Map.of("cf_a", 1));
        assertThat(r.normalized().keySet()).containsExactly("cf_b", "cf_a");
        assertThat(r.normalized().get("cf_b")).isNull();
    }

    @Test
    void unknownAsTypePassesValueThroughUnchanged() {
        List<FieldDefinition> fields = List.of(f("cf_x", "something_new"));
        Map<String, Object> value = Map.of("k", "v");
        assertThat(write(fields, Map.of("cf_x", value)).normalized().get("cf_x")).isEqualTo(value);
    }
}
