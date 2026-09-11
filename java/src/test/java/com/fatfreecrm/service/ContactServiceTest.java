package com.fatfreecrm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Contact;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic parts of {@link ContactService}, pinned to the Rails originals: {@code Contact#full_name}
 * (app/models/entities/contact.rb, default {@code first_name_position = "before"}) and the
 * constants Rails hard-codes ({@code limit(10)} in {@code ApplicationController#auto_complete},
 * {@code permissions.asset_type = 'Contact'}).
 */
class ContactServiceTest {

    @Test
    void fullNameIsFirstNameSpaceLastNameLikeRails() {
        assertThat(ContactService.fullName(contact("Bob", "Dillion"))).isEqualTo("Bob Dillion");
        assertThat(ContactService.fullName(contact("Shamus", "O'Connell"))).isEqualTo("Shamus O'Connell");
    }

    @Test
    void fullNameKeepsTheSeparatorWhenAPartIsBlankLikeRailsStringInterpolation() {
        // Rails columns default to '' (NOT NULL DEFAULT ''), and "#{first_name} #{last_name}" keeps the space
        assertThat(ContactService.fullName(contact("Cher", ""))).isEqualTo("Cher ");
        assertThat(ContactService.fullName(contact("", "Prince"))).isEqualTo(" Prince");
    }

    @Test
    void autocompleteLimitAndAssetTypeMatchRails() {
        assertThat(ContactService.AUTOCOMPLETE_LIMIT).isEqualTo(10);
        assertThat(ContactService.ASSET_TYPE).isEqualTo("Contact");
    }

    private static Contact contact(String firstName, String lastName) {
        Contact contact = new Contact();
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        return contact;
    }
}
