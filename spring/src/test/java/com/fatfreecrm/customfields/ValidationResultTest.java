package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ValidationResult} carries the Rails {@code errors} hash shape and must be immutable once built. */
class ValidationResultTest {

    @Test
    void okOnlyWhenThereAreNoErrors() {
        assertThat(new ValidationResult(Map.of("cf_a", "x"), Map.of()).ok()).isTrue();
        assertThat(new ValidationResult(Map.of(), Map.of("cf_a", List.of("can't be blank"))).ok()).isFalse();
    }

    @Test
    void inputsAreCopiedSoLaterMutationDoesNotLeakIn() {
        Map<String, Object> normalized = new LinkedHashMap<>();
        List<String> boxes = new ArrayList<>(List.of("a"));
        normalized.put("cf_boxes", boxes);
        Map<String, List<String>> errors = new LinkedHashMap<>();
        List<String> messages = new ArrayList<>(List.of("is too short (minimum is 3 characters)"));
        errors.put("cf_min", messages);

        ValidationResult result = new ValidationResult(normalized, errors);
        normalized.put("cf_other", "late");
        boxes.add("b");
        errors.put("cf_other", List.of("late"));
        messages.add("late");

        assertThat(result.normalized()).containsOnlyKeys("cf_boxes");
        assertThat(result.normalized().get("cf_boxes")).isEqualTo(List.of("a"));
        assertThat(result.errors()).containsOnlyKeys("cf_min");
        assertThat(result.errors().get("cf_min")).containsExactly("is too short (minimum is 3 characters)");
    }

    @Test
    void accessorsReturnUnmodifiableViewsPreservingInsertionOrder() {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("cf_z", "1");
        normalized.put("cf_a", List.of("x"));
        Map<String, List<String>> errors = new LinkedHashMap<>();
        errors.put("cf_z", List.of("first"));
        errors.put("cf_a", List.of("second"));
        ValidationResult result = new ValidationResult(normalized, errors);

        assertThat(result.normalized().keySet()).containsExactly("cf_z", "cf_a");
        assertThat(result.errors().keySet()).containsExactly("cf_z", "cf_a");
        assertThatThrownBy(() -> result.normalized().put("cf_new", "v"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.errors().put("cf_new", List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.errors().get("cf_z").add("more"))
            .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) result.normalized().get("cf_a");
        assertThatThrownBy(() -> list.add("y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullNormalizedValuesSurviveTheCopy() {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("cf_cleared", null);

        ValidationResult result = new ValidationResult(normalized, Map.of());

        assertThat(result.normalized()).containsKey("cf_cleared");
        assertThat(result.normalized().get("cf_cleared")).isNull();
    }
}
