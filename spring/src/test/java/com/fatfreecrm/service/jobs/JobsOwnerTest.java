package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fatfreecrm.config.JobsProperties;
import org.junit.jupiter.api.Test;

class JobsOwnerTest {

    @Test
    void defaultsToRailsOwner() {
        assertEquals("rails", new JobsOwner(new JobsProperties()).value());
        assertFalse(new JobsOwner(new JobsProperties()).isSpring());
    }

    @Test
    void startsSpringOnlyForExplicitSpringOwner() {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");

        JobsOwner owner = new JobsOwner(properties);

        assertTrue(owner.isSpring());
        assertEquals("spring", owner.value());
    }

    @Test
    void rejectsUnknownOwner() {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("both");

        assertThrows(IllegalStateException.class, () -> new JobsOwner(properties).validateOwner());
    }
}
