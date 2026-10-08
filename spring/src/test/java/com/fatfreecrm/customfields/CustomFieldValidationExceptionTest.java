package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomFieldValidationExceptionTest {

    @Test
    void snapshotsTheErrorsHashAndExposesItReadOnly() {
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
