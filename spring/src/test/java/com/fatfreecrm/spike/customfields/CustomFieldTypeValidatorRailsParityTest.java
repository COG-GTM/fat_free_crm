package com.fatfreecrm.spike.customfields;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.spike.customfields.CustomFieldTypeValidator.Mode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link CustomFieldTypeValidator} to the Rails behaviour it claims to mirror,
 * deriving every expectation from the Ruby source rather than from the Java code:
 * {@code CustomField#custom_validator} / {@code CustomFieldDatePair#custom_validator}
 * (app/models/fields), {@code CustomFieldPair.create_pair} (required copied from the
 * start half), Ruby {@code Object#blank?} and {@code String#length}, the
 * {@code activerecord.errors.models.custom_field.*} messages in
 * config/locales/fat_free_crm.en-US.yml, and PostgreSQL {@code numeric(15,2)} rounding.
 */
class CustomFieldTypeValidatorRailsParityTest {

    private static final Path RAILS_LOCALE =
        Path.of("..", "config", "locales", "fat_free_crm.en-US.yml");

    private static FieldDefinition f(String name, String label, String as, boolean required,
        Integer min, Integer max, List<String> collection, Long id, Long pairId) {
        return new FieldDefinition(name, label, as, required, min, max, collection, id, pairId);
    }

    private static ValidationResult write(List<FieldDefinition> fields, Map<String, Object> input) {
        return new CustomFieldTypeValidator().validate(fields, input, Mode.WRITE);
    }

    // --- error messages: activerecord.errors.models.custom_field.* -----------------

    /**
     * Reads the {@code activerecord.errors.models.custom_field} block of the Rails locale
     * file and strips the leading {@code ^} that the custom_error_message gem uses to
     * suppress the attribute-name prefix. The block is extracted textually because the
     * locale file uses Psych-specific tags ({@code ! '*'}) elsewhere that SnakeYAML's
     * SafeConstructor rejects.
     */
    private static Map<String, String> railsCustomFieldMessages() throws IOException {
        assertThat(RAILS_LOCALE).as("Rails locale file next to the spring module").exists();
        List<String> lines = Files.readAllLines(RAILS_LOCALE);
        Pattern entry = Pattern.compile("^(\\s+)([a-z_]+):\\s*(\\^?)(.*?)\\s*$");
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        int blockIndent = -1;
        for (String line : lines) {
            if (blockIndent < 0) {
                Matcher m = Pattern.compile("^(\\s+)custom_field:\\s*$").matcher(line);
                if (m.matches()) {
                    blockIndent = m.group(1).length();
                }
                continue;
            }
            Matcher m = entry.matcher(line);
            if (!m.matches() || m.group(1).length() <= blockIndent) {
                break;
            }
            out.put(m.group(2), m.group(4));
        }
        assertThat(out).as("custom_field messages parsed from the Rails locale")
            .containsKeys("required", "minlength", "maxlength", "endbeforestart");
        return out;
    }

    private static String railsMessage(Map<String, String> templates, String key, String label) {
        assertThat(templates).containsKey(key);
        return templates.get(key).replace("%{field}", label);
    }

    @Test
    void errorMessagesMatchRailsLocaleTemplatesInterpolatedWithTheLabel() throws IOException {
        Map<String, String> rails = railsCustomFieldMessages();
        List<FieldDefinition> fields = List.of(
            f("cf_segment", "Segment", "string", true, 3, 5, null, null, null),
            f("cf_event_from", "Event", "date_pair", false, null, null, null, 10L, null),
            f("cf_event_to", "Event (end)", "date_pair", false, null, null, null, 11L, 10L));

        assertThat(write(fields, Map.of("cf_segment", "")).errors().get("cf_segment"))
            .contains(railsMessage(rails, "required", "Segment"));
        assertThat(write(fields, Map.of("cf_segment", "ab")).errors().get("cf_segment"))
            .containsExactly(railsMessage(rails, "minlength", "Segment"));
        assertThat(write(fields, Map.of("cf_segment", "abcdef")).errors().get("cf_segment"))
            .containsExactly(railsMessage(rails, "maxlength", "Segment"));
        // endbeforestart interpolates the START half's label (start.label in Ruby)
        assertThat(write(fields, Map.of("cf_segment", "abcd",
            "cf_event_from", "2024-02-01", "cf_event_to", "2024-01-01")).errors().get("cf_event_to"))
            .containsExactly(railsMessage(rails, "endbeforestart", "Event"));
    }

    @Test
    void errorsAreKeyedByColumnNameAndWordedWithTheLabel() {
        // Rails: obj.errors.add(name.to_sym, I18n.t(..., field: label))
        List<FieldDefinition> fields =
            List.of(f("cf_segment", "Customer segment", "string", true, null, null, null, null, null));
        ValidationResult r = write(fields, Map.of("cf_segment", ""));
        assertThat(r.errors()).containsOnlyKeys("cf_segment");
        assertThat(r.errors().get("cf_segment")).containsExactly("Customer segment is required.");
        assertThat(r.errors().get("cf_segment")).noneMatch(m -> m.contains("cf_segment"));
    }

    @Test
    void pairErrorIsKeyedOnTheEndHalfAndNamesTheStartHalf() {
        // CustomFieldDatePair#custom_validator runs on the half that has pair_id (the end)
        // and adds the error to its own name with field: start.label
        List<FieldDefinition> fields = List.of(
            f("cf_contract_start", "Contract", "date_pair", false, null, null, null, 1L, null),
            f("cf_contract_end", "Contract end", "date_pair", false, null, null, null, 2L, 1L));
        ValidationResult r = write(fields,
            Map.of("cf_contract_start", "2024-06-01", "cf_contract_end", "2024-05-31"));
        assertThat(r.errors()).containsOnlyKeys("cf_contract_end");
        assertThat(r.errors().get("cf_contract_end"))
            .containsExactly("Contract cannot end before it begins.");
    }

    // --- required: Ruby Object#blank? --------------------------------------------

    @Test
    void requiredTreatsEmptyAndBlankOnlyArraysAsBlank() {
        // [].blank? == true; a check_boxes array of blanks normalizes to [] which is blank too
        List<FieldDefinition> fields =
            List.of(f("cf_tags", "Tags", "check_boxes", true, null, null, null, null, null));
        assertThat(write(fields, Map.of("cf_tags", List.of())).errors().get("cf_tags"))
            .containsExactly("Tags is required.");
        assertThat(write(fields, Map.of("cf_tags", List.of("", "  "))).errors().get("cf_tags"))
            .containsExactly("Tags is required.");
        assertThat(write(fields, Map.of("cf_tags", List.of("a"))).ok()).isTrue();
    }

    @Test
    void requiredDoesNotTreatZeroAsBlank() {
        // 0.blank? == false, 0.0.blank? == false, "0".blank? == false
        assertThat(write(List.of(f("cf_i", "I", "integer", true, null, null, null, null, null)),
            Map.of("cf_i", 0)).ok()).isTrue();
        assertThat(write(List.of(f("cf_i", "I", "integer", true, null, null, null, null, null)),
            Map.of("cf_i", "0")).ok()).isTrue();
        assertThat(write(List.of(f("cf_d", "D", "decimal", true, null, null, null, null, null)),
            Map.of("cf_d", "0.00")).ok()).isTrue();
        assertThat(write(List.of(f("cf_f", "F", "float", true, null, null, null, null, null)),
            Map.of("cf_f", 0.0d)).ok()).isTrue();
        assertThat(write(List.of(f("cf_s", "S", "string", true, null, null, null, null, null)),
            Map.of("cf_s", "0")).ok()).isTrue();
    }

    @Test
    void requiredAndLengthErrorsAccumulateInRailsOrder() {
        // custom_validator adds required, then minlength, then maxlength, each independently
        List<FieldDefinition> fields =
            List.of(f("cf_code", "Code", "string", true, 3, null, null, null, null));
        assertThat(write(fields, Map.of("cf_code", "")).errors().get("cf_code"))
            .containsExactly("Code is required.", "Code is too short.");
    }

    // --- minlength / maxlength: Ruby String#length counts code points ----------------

    @Test
    void lengthLimitsCountCodePointsLikeRubyStringLength() {
        String twoEmoji = "\uD83D\uDE00\uD83D\uDE00"; // "😀😀": Ruby length 2, Java length() 4
        List<FieldDefinition> maxTwo =
            List.of(f("cf_s", "S", "string", false, null, 2, null, null, null));
        assertThat(write(maxTwo, Map.of("cf_s", twoEmoji)).ok()).isTrue();
        assertThat(write(maxTwo, Map.of("cf_s", "\u65E5\u672C\u8A9E")).errors().get("cf_s")) // 日本語
            .containsExactly("S is too long.");

        List<FieldDefinition> minThree =
            List.of(f("cf_s", "S", "string", false, 3, null, null, null, null));
        assertThat(write(minThree, Map.of("cf_s", twoEmoji)).errors().get("cf_s"))
            .containsExactly("S is too short.");
        assertThat(write(minThree, Map.of("cf_s", "\u65E5\u672C\u8A9E")).ok()).isTrue();
    }

    @Test
    void zeroLengthLimitsAreDisabledLikeRailsToIGreaterThanZero() {
        // Rails: (minlength.to_i > 0) && ..., (maxlength.to_i > 0) && ...
        List<FieldDefinition> fields =
            List.of(f("cf_s", "S", "string", false, 0, 0, null, null, null));
        assertThat(write(fields, Map.of("cf_s", "")).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_s", "x".repeat(500))).ok()).isTrue();
    }

    @Test
    void lengthLimitsBoundariesAreInclusive() {
        // length < minlength and length > maxlength are the only failing cases
        List<FieldDefinition> fields =
            List.of(f("cf_s", "S", "string", false, 3, 3, null, null, null));
        assertThat(write(fields, Map.of("cf_s", "abc")).ok()).isTrue();
    }

    // --- pairs: CustomFieldDatePair#custom_validator + CustomFieldPair.create_pair -------

    private static List<FieldDefinition> datetimePair(boolean startRequired, boolean endRequired) {
        return List.of(
            f("cf_start", "Window", "datetime_pair", startRequired, null, null, null, 1L, null),
            f("cf_end", "Window end", "datetime_pair", endRequired, null, null, null, 2L, 1L));
    }

    @Test
    void pairCheckOnlyRunsWhenBothHalvesArePresent() {
        // from.present? && to.present? && from > to
        List<FieldDefinition> fields = datetimePair(false, false);
        assertThat(write(fields, Map.of("cf_end", "2024-01-31T09:00:00Z")).ok()).isTrue();
        assertThat(write(fields, Map.of("cf_start", "2024-01-31T09:00:00Z")).ok()).isTrue();
        assertThat(write(fields, Map.of()).ok()).isTrue();
        // an unparseable start half yields its own error but no pair error on the end half
        ValidationResult r = write(fields,
            Map.of("cf_start", "garbage", "cf_end", "2024-01-31T09:00:00Z"));
        assertThat(r.errors()).containsOnlyKeys("cf_start");
    }

    @Test
    void pairComparesInstantsNotTextAcrossUtcOffsets() {
        // Rails compares Time values; 10:00+02:00 is 08:00Z, i.e. before a 09:00Z start
        List<FieldDefinition> fields = datetimePair(false, false);
        assertThat(write(fields, Map.of(
            "cf_start", "2024-01-31T09:00:00Z",
            "cf_end", "2024-01-31T10:00:00+02:00")).errors().get("cf_end"))
            .containsExactly("Window cannot end before it begins.");
        // 12:00+02:00 is 10:00Z, after the start
        assertThat(write(fields, Map.of(
            "cf_start", "2024-01-31T09:00:00Z",
            "cf_end", "2024-01-31T12:00:00+02:00")).ok()).isTrue();
    }

    @Test
    void endHalfRequiredFollowsTheStartHalfNotItsOwnFlag() {
        // create_pair/update_pair always write field2.required = field1.required
        assertThat(write(datetimePair(false, true), Map.of()).ok()).isTrue();
        ValidationResult r = write(datetimePair(true, false), Map.of());
        assertThat(r.errors()).containsOnlyKeys("cf_start", "cf_end");
        assertThat(r.errors().get("cf_end")).containsExactly("Window end is required.");
    }

    @Test
    void datePairErrorAlsoAppliesToDateHalvesWithRequiredAndLength() {
        // CustomFieldDatePair#custom_validator calls super first: the end half still gets
        // the common required check before the endbeforestart check
        List<FieldDefinition> fields = List.of(
            f("cf_from", "Period", "date_pair", true, null, null, null, 1L, null),
            f("cf_to", "Period end", "date_pair", true, null, null, null, 2L, 1L));
        Map<String, Object> input = new java.util.HashMap<>();
        input.put("cf_from", "2024-02-01");
        input.put("cf_to", null); // Rails casts a blank date param to nil before validation
        ValidationResult r = write(fields, input);
        assertThat(r.errors()).containsOnlyKeys("cf_to");
        assertThat(r.errors().get("cf_to")).containsExactly("Period end is required.");
    }

    // --- decimal: PostgreSQL numeric(15,2) ----------------------------------------

    @Test
    void decimalRoundsHalfAwayFromZeroLikePostgresNumeric() {
        List<FieldDefinition> fields = List.of(f("cf_m", "M", "decimal", false, null, null, null, null, null));
        assertThat(write(fields, Map.of("cf_m", "-12.345")).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("-12.35"));
        assertThat(write(fields, Map.of("cf_m", "12.344999")).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("12.34"));
        assertThat(write(fields, Map.of("cf_m", "7")).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("7.00"));
    }

    @Test
    void decimalAcceptsThirteenIntegerDigitsAndRejectsRoundingIntoFourteen() {
        List<FieldDefinition> fields = List.of(f("cf_m", "M", "decimal", false, null, null, null, null, null));
        assertThat(write(fields, Map.of("cf_m", "9999999999999.99")).normalized().get("cf_m"))
            .isEqualTo(new BigDecimal("9999999999999.99"));
        assertThat(write(fields, Map.of("cf_m", "-9999999999999.99")).ok()).isTrue();
        // numeric(15,2) overflows once rounding carries into a 14th integer digit
        assertThat(write(fields, Map.of("cf_m", "9999999999999.995")).errors().get("cf_m"))
            .containsExactly("M is out of range.");
    }

    // --- integer: PostgreSQL int4 ------------------------------------------------

    @Test
    void integerBoundariesMatchPostgresInt4() {
        List<FieldDefinition> fields = List.of(f("cf_i", "I", "integer", false, null, null, null, null, null));
        assertThat(write(fields, Map.of("cf_i", 2147483647L)).normalized().get("cf_i"))
            .isEqualTo(Integer.MAX_VALUE);
        assertThat(write(fields, Map.of("cf_i", -2147483648L)).normalized().get("cf_i"))
            .isEqualTo(Integer.MIN_VALUE);
        assertThat(write(fields, Map.of("cf_i", -2147483649L)).errors().get("cf_i"))
            .containsExactly("I is out of range.");
        // "3.0" is an integral value: Rails would store 3, and so does the prototype
        assertThat(write(fields, Map.of("cf_i", "3.0")).normalized().get("cf_i")).isEqualTo(3);
    }
}
