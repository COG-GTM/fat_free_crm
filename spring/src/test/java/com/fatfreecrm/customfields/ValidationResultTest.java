package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationResultTest {

    @Test
    void isOkOnlyWhenNoFieldHasErrors() {
        assertThat(new ValidationResult(Map.of("cf_a", "x"), Map.of()).ok()).isTrue();
        assertThat(new ValidationResult(Map.of(), Map.of("cf_a", List.of("is required"))).ok()).isFalse();
    }

    @Test
    void snapshotsNormalizedValuesAndErrorsDefensively() {
        Map<String, Object> normalized = new LinkedHashMap<>();
        List<String> boxes = new ArrayList<>(List.of("a"));
        normalized.put("cf_boxes", boxes);
        Map<String, List<String>> errors = new LinkedHashMap<>();
        List<String> messages = new ArrayList<>(List.of("is too short"));
        errors.put("cf_name", messages);
        ValidationResult result = new ValidationResult(normalized, errors);
        boxes.add("b");
        messages.add("late");
        normalized.put("cf_other", 1);
        errors.put("cf_other", List.of("late"));

        assertThat(result.normalized()).containsOnlyKeys("cf_boxes");
        assertThat(result.normalized().get("cf_boxes")).isEqualTo(List.of("a"));
        assertThat(result.errors()).containsOnlyKeys("cf_name");
        assertThat(result.errors().get("cf_name")).containsExactly("is too short");
        assertThatThrownBy(() -> result.normalized().put("cf_x", 1))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.errors().put("cf_x", List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) result.normalized().get("cf_boxes")).remove(0))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validationExceptionCarriesAnImmutableCopyOfTheRailsStyleErrors() {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        errors.put("cf_name", List.of("Name is required"));
        CustomFieldValidationException exception = new CustomFieldValidationException(errors);
        errors.put("cf_late", List.of("late"));

        assertThat(exception.getMessage()).isEqualTo("Custom field validation failed");
        assertThat(exception.errors()).containsOnlyKeys("cf_name");
        assertThatThrownBy(() -> exception.errors().put("cf_x", List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
