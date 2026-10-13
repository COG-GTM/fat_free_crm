package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.AccountOpportunity;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.ContactOpportunity;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.support.RailsModelType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the AB-272 Phase B {@link PaperTrailOptions} registry entries to the Rails
 * {@code has_paper_trail} declarations in app/models/entities/{account_contact,account_opportunity}.rb,
 * app/models/polymorphic/address.rb and the five commentable entities.
 */
class PaperTrailOptionsEntityTrailsTest {

    @Test
    void accountContactIgnoresOwnColumnsAndRelatesToTheContact() {
        PaperTrailOptions options = PaperTrailOptions.forClass(AccountContact.class).orElseThrow();
        assertThat(options.itemType()).isEqualTo(RailsModelType.ACCOUNT_CONTACT);
        assertThat(options.itemType().railsName()).isEqualTo("AccountContact");
        assertThat(options.ignore()).containsExactlyInAnyOrder("id", "created_at", "updated_at", "contact_id");
        assertThat(options.relatedAttribute()).isEqualTo("contact");
    }

    @Test
    void accountOpportunityTracksEverythingWithoutRelatedMeta() {
        PaperTrailOptions options = PaperTrailOptions.forClass(AccountOpportunity.class).orElseThrow();
        assertThat(options.itemType().railsName()).isEqualTo("AccountOpportunity");
        assertThat(options.ignore()).isEmpty();
        assertThat(options.relatedAttribute()).isNull();
    }

    @Test
    void addressRelatesToThePolymorphicAddressable() {
        PaperTrailOptions options = PaperTrailOptions.forClass(Address.class).orElseThrow();
        assertThat(options.itemType().railsName()).isEqualTo("Address");
        assertThat(options.ignore()).isEmpty();
        assertThat(options.relatedAttribute()).isEqualTo("addressable");
    }

    @Test
    void contactOpportunityHasNoTrailBecauseRailsCommentedItOut() {
        assertThat(PaperTrailOptions.forClass(ContactOpportunity.class)).isEmpty();
    }

    @Test
    void everyCommentableEntityIgnoresOnlySubscribedUsers() {
        for (Class<?> type : List.of(Account.class, Campaign.class, Contact.class, Lead.class, Opportunity.class)) {
            PaperTrailOptions options = PaperTrailOptions.forClass(type).orElseThrow();
            assertThat(options.ignore()).as(type.getSimpleName()).containsExactly("subscribed_users");
            assertThat(options.relatedAttribute()).as(type.getSimpleName()).isNull();
            assertThat(options.itemType().railsName()).isEqualTo(type.getSimpleName());
        }
    }
}
