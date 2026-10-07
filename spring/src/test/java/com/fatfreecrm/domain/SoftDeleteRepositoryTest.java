package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountOpportunityRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactOpportunityRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.EmailRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

class SoftDeleteRepositoryTest extends AbstractPostgresIntegrationTest {

    private static final Instant DELETED_AT = Instant.parse("2025-03-04T05:06:07Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private OpportunityRepository opportunityRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private AccountContactRepository accountContactRepository;

    @Autowired
    private AccountOpportunityRepository accountOpportunityRepository;

    @Autowired
    private ContactOpportunityRepository contactOpportunityRepository;

    @Test
    @Transactional
    void normalQueriesIncludeRowsWithDeletedAtLikeRails() {
        Account liveAccount = new Account();
        liveAccount.setName("Live account");
        Account deletedAccount = new Account();
        deletedAccount.setName("Deleted account");
        verify(accountRepository, accountRepository, Account::getId, Account::getDeletedAt,
            Account::setDeletedAt, liveAccount, deletedAccount);

        Campaign liveCampaign = new Campaign();
        liveCampaign.setName("Live campaign");
        Campaign deletedCampaign = new Campaign();
        deletedCampaign.setName("Deleted campaign");
        verify(campaignRepository, campaignRepository, Campaign::getId, Campaign::getDeletedAt,
            Campaign::setDeletedAt, liveCampaign, deletedCampaign);

        Contact liveContact = new Contact();
        liveContact.setFirstName("Live");
        liveContact.setLastName("Contact");
        Contact deletedContact = new Contact();
        deletedContact.setFirstName("Deleted");
        deletedContact.setLastName("Contact");
        verify(contactRepository, contactRepository, Contact::getId, Contact::getDeletedAt,
            Contact::setDeletedAt, liveContact, deletedContact);

        Lead liveLead = new Lead();
        liveLead.setFirstName("Live");
        liveLead.setLastName("Lead");
        Lead deletedLead = new Lead();
        deletedLead.setFirstName("Deleted");
        deletedLead.setLastName("Lead");
        verify(leadRepository, leadRepository, Lead::getId, Lead::getDeletedAt,
            Lead::setDeletedAt, liveLead, deletedLead);

        Opportunity liveOpportunity = new Opportunity();
        liveOpportunity.setName("Live opportunity");
        Opportunity deletedOpportunity = new Opportunity();
        deletedOpportunity.setName("Deleted opportunity");
        verify(opportunityRepository, opportunityRepository, Opportunity::getId, Opportunity::getDeletedAt,
            Opportunity::setDeletedAt, liveOpportunity, deletedOpportunity);

        Task liveTask = new Task();
        liveTask.setName("Live task");
        Task deletedTask = new Task();
        deletedTask.setName("Deleted task");
        verify(taskRepository, taskRepository, Task::getId, Task::getDeletedAt,
            Task::setDeletedAt, liveTask, deletedTask);

        Email liveEmail = new Email();
        liveEmail.setImapMessageId("live-email");
        liveEmail.setSentFrom("live@example.test");
        liveEmail.setSentTo("recipient@example.test");
        Email deletedEmail = new Email();
        deletedEmail.setImapMessageId("deleted-email");
        deletedEmail.setSentFrom("deleted@example.test");
        deletedEmail.setSentTo("recipient@example.test");
        verify(emailRepository, null, Email::getId, Email::getDeletedAt,
            Email::setDeletedAt, liveEmail, deletedEmail);

        Address liveAddress = new Address();
        liveAddress.setAddressType("Business");
        Address deletedAddress = new Address();
        deletedAddress.setAddressType("Billing");
        verify(addressRepository, null, Address::getId, Address::getDeletedAt,
            Address::setDeletedAt, liveAddress, deletedAddress);

        verify(accountContactRepository, null, AccountContact::getId, AccountContact::getDeletedAt,
            AccountContact::setDeletedAt, new AccountContact(), new AccountContact());
        verify(accountOpportunityRepository, null, AccountOpportunity::getId, AccountOpportunity::getDeletedAt,
            AccountOpportunity::setDeletedAt, new AccountOpportunity(), new AccountOpportunity());
        verify(contactOpportunityRepository, null, ContactOpportunity::getId, ContactOpportunity::getDeletedAt,
            ContactOpportunity::setDeletedAt, new ContactOpportunity(), new ContactOpportunity());
    }

    private <T> void verify(
        JpaRepository<T, Long> repository,
        JpaSpecificationExecutor<T> specificationExecutor,
        Function<T, Long> id,
        Function<T, Instant> deletedAt,
        BiConsumer<T, Instant> setDeletedAt,
        T live,
        T deleted
    ) {
        repository.saveAndFlush(live);
        setDeletedAt.accept(deleted, DELETED_AT);
        repository.saveAndFlush(deleted);
        Long liveId = id.apply(live);
        Long deletedId = id.apply(deleted);

        entityManager.clear();

        List<T> all = repository.findAll();
        assertThat(all).anyMatch(value -> id.apply(value).equals(liveId));
        T fromAll = all.stream().filter(value -> id.apply(value).equals(deletedId)).findFirst().orElseThrow();
        assertThat(deletedAt.apply(fromAll)).isEqualTo(DELETED_AT);
        assertThat(repository.findById(deletedId).orElseThrow())
            .extracting(deletedAt)
            .isEqualTo(DELETED_AT);

        if (specificationExecutor != null) {
            Specification<T> allRows = (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
            T fromSpecification = specificationExecutor.findAll(allRows).stream()
                .filter(value -> id.apply(value).equals(deletedId))
                .findFirst()
                .orElseThrow();
            assertThat(deletedAt.apply(fromSpecification)).isEqualTo(DELETED_AT);
        }
    }
}
