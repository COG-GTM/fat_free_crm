package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactOpportunityRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.UserRepository;
import org.junit.jupiter.api.Test;

class RailsResourcesAutocompleteTest {

    @Test
    void rendersNullLastNamesAsRubyInterpolationDoes() {
        RailsResources resources = new RailsResources(
            mock(AccountRepository.class),
            mock(AccountContactRepository.class),
            mock(CampaignRepository.class),
            mock(ContactOpportunityRepository.class),
            mock(ContactRepository.class),
            mock(LeadRepository.class),
            mock(OpportunityRepository.class),
            mock(TaskRepository.class),
            mock(UserRepository.class)
        );
        Contact contact = new Contact();
        contact.setFirstName("Ada");
        contact.setLastName(null);
        Lead lead = new Lead();
        lead.setFirstName("Grace");
        lead.setLastName(null);

        assertThat(resources.contact.autocompleteText().apply(contact)).isEqualTo("Ada ");
        assertThat(resources.lead.autocompleteText().apply(lead)).isEqualTo("Grace ");
    }
}
