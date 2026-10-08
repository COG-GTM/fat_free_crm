package com.fatfreecrm.service.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ActiveModelMessagesTest {

    private static final ActiveModelMessages MESSAGES = new ActiveModelMessages();

    @Test
    void blankMessageComesFromErrorsMessages() {
        assertEquals("can't be blank",
            MESSAGES.generateMessage("task", "comment", "blank", null));
    }

    @Test
    void modelAttributeKeyOverridesGeneric() {
        assertEquals("^Please specify task name.",
            MESSAGES.generateMessage("task", "name", "missing_task_name", null));
    }

    @Test
    void takenMessageResolves() {
        assertEquals("has already been taken",
            MESSAGES.generateMessage("user", "username", "taken", Map.of("value", "admin")));
    }

    @Test
    void unknownKeyFallsBackToInvalid() {
        assertEquals("is invalid", MESSAGES.generateMessage("task", "name", "no_such_key", null));
    }

    @Test
    void fullMessageStripsCaret() {
        assertEquals("Please specify task name.",
            MESSAGES.fullMessage("task", "name", "^Please specify task name."));
    }

    @Test
    void fullMessagePrefixedViaFormat() {
        String full = MESSAGES.fullMessage("task", "name", "can't be blank");
        assertEquals("Name can't be blank", full);
    }

    @Test
    void humanAttributeNameHumanizesUnderscores() {
        assertEquals("Due at", MESSAGES.humanAttributeName("task", "due_at"));
    }
}
