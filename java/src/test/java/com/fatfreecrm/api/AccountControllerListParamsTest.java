package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.api.AccountController.ListParams;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link AccountController.ListParams}: the alias-resolved {@code perPage}/{@code sortBy} pair
 * that is Bean-Validated as a whole, and the saturating {@code perPage} parse that feeds
 * {@code PaginationProperties.clampPageSize}. Rails clamps {@code per_page} with
 * {@code [1, [per_page, 200].min].max} after {@code to_i}; the Java contract rejects
 * non-positive and non-integer spellings with 400 instead and only clamps upward.
 */
class AccountControllerListParamsTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @CsvSource({
            "1, 1",
            "20, 20",
            "200, 200",
            "201, 201",
            "' 5 ', 5",
            "2147483647, 2147483647",
    })
    void perPageParsesPositiveIntegersIgnoringSurroundingWhitespace(String raw, int expected) {
        assertThat(new ListParams(raw, null).perPageValue()).isEqualTo(expected);
        assertThat(violations(new ListParams(raw, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "9999999999", "99999999999999999999", "1000000000000000000000000000000"})
    void perPageBeyondIntegerRangeSaturatesInsteadOfOverflowing(String raw) {
        assertThat(new ListParams(raw, null).perPageValue()).isEqualTo(Integer.MAX_VALUE);
        assertThat(violations(new ListParams(raw, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "007", "+5", "1.5", "abc", "1e3", "", " ", "5 5", "0x10"})
    void perPageThatIsNotAPositiveIntegerFailsValidation(String raw) {
        Set<ConstraintViolation<ListParams>> violations = violations(new ListParams(raw, null));

        assertThat(violations).hasSize(1);
        ConstraintViolation<ListParams> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("perPage");
        assertThat(violation.getMessage()).isEqualTo("must be a positive integer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "name DESC", "rating ASC", "id", "name ASC extra", "name; drop table"})
    void unknownSortByFailsValidationWithTheRailsOrderList(String raw) {
        Set<ConstraintViolation<ListParams>> violations = violations(new ListParams(null, raw));

        assertThat(violations).hasSize(1);
        ConstraintViolation<ListParams> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("sortBy");
        assertThat(violation.getMessage())
                .isEqualTo("must be one of: name ASC, rating DESC, created_at DESC, updated_at DESC");
    }

    @ParameterizedTest
    @ValueSource(strings = {"name ASC", "name", "rating DESC", "created_at DESC", "createdAt", "updated_at DESC", "  updatedAt  "})
    void knownSortByPassesValidation(String raw) {
        assertThat(violations(new ListParams(null, raw))).isEmpty();
    }

    @Test
    void absentParametersAreValidAndPerPageValueIsNull() {
        ListParams params = new ListParams(null, null);

        assertThat(violations(params)).isEmpty();
        assertThat(params.perPageValue()).isNull();
        assertThat(params.sortBy()).isNull();
    }

    @Test
    void bothFieldsAreReportedWhenBothAreInvalid() {
        Set<ConstraintViolation<ListParams>> violations = violations(new ListParams("0", "bogus"));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactlyInAnyOrder("perPage", "sortBy");
    }

    private static Set<ConstraintViolation<ListParams>> violations(ListParams params) {
        return VALIDATOR.validate(params);
    }
}
