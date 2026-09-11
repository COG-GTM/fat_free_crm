package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Contact;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic parity checks for {@link ContactService}; the query/visibility wiring is exercised
 * end-to-end in {@code ContactControllerIT}.
 */
class ContactServiceTest {

    @Test
    void fullNameIsFirstSpaceLastLikeRailsDefaultFormat() {
        assertThat(ContactService.fullName(contact("Alice", "Anderson"))).isEqualTo("Alice Anderson");
    }

    @Test
    void fullNameKeepsTheSeparatorWhenEitherPartIsEmpty() {
        // Rails: "#{first_name} #{last_name}" with the not-null "" column defaults
        assertThat(ContactService.fullName(contact("Alice", ""))).isEqualTo("Alice ");
        assertThat(ContactService.fullName(contact("", "Anderson"))).isEqualTo(" Anderson");
        assertThat(ContactService.fullName(contact("", ""))).isEqualTo(" ");
    }

    @Test
    void fullNameDoesNotTrimOrCollapseWhitespaceInsideNames() {
        assertThat(ContactService.fullName(contact("Mary Ann", "van der Berg"))).isEqualTo("Mary Ann van der Berg");
    }

    @Test
    void constantsMatchTheRailsController() {
        assertThat(ContactService.ASSET_TYPE).isEqualTo("Contact");
        assertThat(ContactService.AUTOCOMPLETE_LIMIT).isEqualTo(10);
    }

    private static Contact contact(String firstName, String lastName) {
        Contact contact = new Contact();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        return contact;
    }
}
