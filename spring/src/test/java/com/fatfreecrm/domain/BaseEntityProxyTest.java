package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BaseEntityProxyTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Test
    void comparesDetachedUninitializedProxyWithoutInitializingIt() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        FixtureIds ids = transaction.execute(status -> {
            Lead lead = new Lead();
            lead.setFirstName("Proxy");
            lead.setLastName("Target");
            leadRepository.saveAndFlush(lead);

            Contact contact = new Contact();
            contact.setFirstName("Proxy");
            contact.setLastName("Reference");
            contact.setLead(lead);
            contactRepository.saveAndFlush(contact);
            return new FixtureIds(contact.getId(), lead.getId());
        });

        Lead proxy = transaction.execute(status -> contactRepository.findById(ids.contactId())
            .orElseThrow().getLead());
        Lead loaded = transaction.execute(status -> leadRepository.findById(ids.leadId()).orElseThrow());

        assertThat(proxy).isInstanceOf(HibernateProxy.class);
        assertThat(Hibernate.isInitialized(proxy)).isFalse();
        assertThat(proxy.hashCode()).isEqualTo(loaded.hashCode());
        assertThat(proxy.equals(loaded)).isTrue();
        assertThat(loaded.equals(proxy)).isTrue();
        assertThat(Hibernate.isInitialized(proxy)).isFalse();
    }

    private record FixtureIds(Long contactId, Long leadId) {
    }
}
