package com.fatfreecrm.service.write.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.service.write.RailsInternalError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins {@code CustomField#db_transition_safety}: {@code :null} when the column type is unchanged,
 * {@code :safe} for string→text and within the date/time and integer/float families, else
 * {@code :unsafe} (no {@code ALTER COLUMN TYPE}).
 */
class AdminFieldWriteServiceTransitionSafetyTest {

    @ParameterizedTest
    @CsvSource({
        "string, email, null",
        "select, radio_buttons, null",
        "text, check_boxes, null",
        "date_pair, date, null",
        "string, text, safe",
        "select, check_boxes, safe",
        "date, datetime, safe",
        "datetime, date, safe",
        "date_pair, datetime_pair, safe",
        "integer, float, safe",
        "float, integer, safe",
        "string, boolean, unsafe",
        "text, string, unsafe",
        "decimal, integer, unsafe",
        "boolean, date, unsafe",
        "check_boxes, select, unsafe",
    })
    void transitionSafetyMirrorsCustomFieldRules(String oldAs, String newAs, String expected) {
        assertThat(AdminFieldWriteService.transitionSafety(oldAs, newAs)).isEqualTo(expected);
    }

    @Test
    void unknownFieldTypesRaiseLikeRailsLookupClass() {
        assertThatThrownBy(() -> AdminFieldWriteService.transitionSafety(null, "text"))
            .isInstanceOf(RailsInternalError.class)
            .hasMessage("Unknown field_type: null");
        assertThatThrownBy(() -> AdminFieldWriteService.transitionSafety("string", "bogus"))
            .isInstanceOf(RailsInternalError.class)
            .hasMessage("Unknown field_type: bogus");
    }
}
