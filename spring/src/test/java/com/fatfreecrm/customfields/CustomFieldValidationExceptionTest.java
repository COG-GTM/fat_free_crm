package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The 422 payload is built from {@link CustomFieldValidationException#errors()}: the top-level hash is
 * snapshotted at construction and read-only. Per-field message lists are passed through as given (the
 * validator already hands over immutable lists), so only the map level is pinned here.
 */
class CustomFieldValidationExceptionTest {

    @Test
    void snapshotsTheTopLevelErrorsHashAndExposesItReadOnly() {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        errors.put("cf_required", List.of("can't be blank"));
        CustomFieldValidationException exception = new CustomFieldValidationException(errors);
        errors.put("cf_other", List.of("late"));

        assertThat(exception.errors()).containsOnlyKeys("cf_required");
        assertThat(exception.errors().get("cf_required")).containsExactly("can't be blank");
        assertThat(exception.getMessage()).isEqualTo("Custom field validation failed");
        assertThatThrownBy(() -> exception.errors().put("cf_new", List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullErrorEntriesEarly() {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        errors.put("cf_null", null);

        assertThatThrownBy(() -> new CustomFieldValidationException(errors))
            .isInstanceOf(NullPointerException.class);
    }
}
