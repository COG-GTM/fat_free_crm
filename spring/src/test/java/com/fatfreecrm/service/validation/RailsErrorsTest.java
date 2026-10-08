package com.fatfreecrm.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RailsErrors} to {@code record.errors.as_json}: attributes in first-added order,
 * messages per attribute in validator order, caret messages kept verbatim, and the rendered map
 * carried unchanged by {@link RailsValidationException}.
 */
class RailsErrorsTest {

    private static final ActiveModelMessages MESSAGES = new ActiveModelMessages();

    @Test
    void keepsAttributeInsertionOrderAndCollectsMessagesPerAttribute() {
        RailsErrors errors = new RailsErrors()
            .add("user", "must exist")
            .add("user", "can't be blank")
            .add("name", "^Please specify task name.");

        Map<String, List<String>> map = errors.asMap();
        assertThat(map.keySet()).containsExactly("user", "name");
        assertThat(map.get("user")).containsExactly("must exist", "can't be blank");
        assertThat(map.get("name")).containsExactly("^Please specify task name.");
        assertThat(errors.isEmpty()).isFalse();
        assertThat(new RailsErrors().isEmpty()).isTrue();
    }

    @Test
    void asMapIsAnUnmodifiableSnapshot() {
        RailsErrors errors = new RailsErrors().add("name", "can't be blank");
        Map<String, List<String>> snapshot = errors.asMap();
        errors.add("url", "can't be blank");

        assertThat(snapshot.keySet()).containsExactly("name");
        assertThatThrownBy(() -> snapshot.put("x", List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.get("name").add("other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void catalogLookupProducesRailsBareMessagesInValidatorOrder() {
        RailsErrors errors = new RailsErrors()
            .add(MESSAGES, "task", "user", "required")
            .add(MESSAGES, "task", "user", "blank")
            .add(MESSAGES, "task", "name", "missing_task_name")
            .add(MESSAGES, "task", "calendar", "invalid_date")
            .add(MESSAGES, "list", "url", "blank");

        assertThat(errors.asMap()).containsExactly(
            Map.entry("user", List.of("must exist", "can't be blank")),
            Map.entry("name", List.of("^Please specify task name.")),
            Map.entry("calendar", List.of("^Please specify valid date.")),
            Map.entry("url", List.of("can't be blank")));
    }

    @Test
    void validationExceptionCarriesTheRenderedErrorsMap() {
        RailsErrors errors = new RailsErrors().add(MESSAGES, "comment", "comment", "blank");
        RailsValidationException exception = new RailsValidationException(errors);

        assertThat(exception.errors()).isEqualTo(Map.of("comment", List.of("can't be blank")));
        assertThat(exception.getMessage()).isEqualTo("Rails-style validation failed");
    }
}
