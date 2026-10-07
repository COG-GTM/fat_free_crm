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
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class SoftDeleteRepositoryTest extends AbstractPostgresIntegrationTest {

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

    @Autowired
    private EntityManager entityManager;

    @Test
    void exposesLiveRowsNormallyAndDeletedRowsOnlyThroughExplicitFinders() {
        Account account = new Account();
        account.setName("soft delete account");
        verify(
            accountRepository, accountRepository::findAllIncludingDeleted, accountRepository::findByIdIncludingDeleted,
            Account::getId, account, new Account()
        );
        Campaign campaign = new Campaign();
        campaign.setName("soft delete campaign");
        verify(campaignRepository, campaignRepository::findAllIncludingDeleted,
            campaignRepository::findByIdIncludingDeleted, Campaign::getId, campaign, new Campaign());
        Contact contact = new Contact();
        contact.setFirstName("live");
        Contact deletedContact = new Contact();
        deletedContact.setFirstName("deleted");
        verify(contactRepository, contactRepository::findAllIncludingDeleted,
            contactRepository::findByIdIncludingDeleted, Contact::getId, contact, deletedContact);
        Lead lead = new Lead();
        lead.setFirstName("live");
        Lead deletedLead = new Lead();
        deletedLead.setFirstName("deleted");
        verify(leadRepository, leadRepository::findAllIncludingDeleted,
            leadRepository::findByIdIncludingDeleted, Lead::getId, lead, deletedLead);
        Opportunity opportunity = new Opportunity();
        opportunity.setName("soft delete opportunity");
        verify(opportunityRepository, opportunityRepository::findAllIncludingDeleted,
            opportunityRepository::findByIdIncludingDeleted, Opportunity::getId, opportunity, new Opportunity());
        Task task = new Task();
        task.setName("soft delete task");
        verify(taskRepository, taskRepository::findAllIncludingDeleted, taskRepository::findByIdIncludingDeleted,
            Task::getId, task, new Task());

        Email email = new Email();
        email.setImapMessageId("soft-delete@example.test");
        email.setSentFrom("from@example.test");
        email.setSentTo("to@example.test");
        Email deletedEmail = new Email();
        deletedEmail.setImapMessageId("deleted@example.test");
        deletedEmail.setSentFrom("from@example.test");
        deletedEmail.setSentTo("to@example.test");
        verify(emailRepository, emailRepository::findAllIncludingDeleted, emailRepository::findByIdIncludingDeleted,
            Email::getId, email, deletedEmail);
        verify(addressRepository, addressRepository::findAllIncludingDeleted,
            addressRepository::findByIdIncludingDeleted, Address::getId, new Address(), new Address());
        verify(accountContactRepository, accountContactRepository::findAllIncludingDeleted,
            accountContactRepository::findByIdIncludingDeleted, AccountContact::getId,
            new AccountContact(), new AccountContact());
        verify(accountOpportunityRepository, accountOpportunityRepository::findAllIncludingDeleted,
            accountOpportunityRepository::findByIdIncludingDeleted, AccountOpportunity::getId,
            new AccountOpportunity(), new AccountOpportunity());
        verify(contactOpportunityRepository, contactOpportunityRepository::findAllIncludingDeleted,
            contactOpportunityRepository::findByIdIncludingDeleted, ContactOpportunity::getId,
            new ContactOpportunity(), new ContactOpportunity());

        assertThat(accountRepository.findAll(isLiveAccount())).extracting(Account::getId).contains(account.getId());
        assertThat(campaignRepository.findAll(isLiveCampaign())).extracting(Campaign::getId).contains(campaign.getId());
        assertThat(contactRepository.findAll(isLiveContact())).extracting(Contact::getId).contains(contact.getId());
        assertThat(leadRepository.findAll(isLiveLead())).extracting(Lead::getId).contains(lead.getId());
        assertThat(opportunityRepository.findAll(isLiveOpportunity()))
            .extracting(Opportunity::getId).contains(opportunity.getId());
        assertThat(taskRepository.findAll(isLiveTask())).extracting(Task::getId).contains(task.getId());
    }

    private <T> void verify(
            JpaRepository<T, Long> repository,
            Supplier<List<T>> includeAll,
            Function<Long, Optional<T>> includeById,
            Function<T, Long> id,
            T live,
            T deleted) {
        repository.saveAndFlush(live);
        markDeleted(deleted);
        repository.saveAndFlush(deleted);
        entityManager.clear();
        assertThat(repository.findAll()).extracting(id).contains(id.apply(live)).doesNotContain(id.apply(deleted));
        assertThat(repository.findById(id.apply(deleted))).isEmpty();
        assertThat(includeAll.get()).extracting(id).contains(id.apply(live), id.apply(deleted));
        assertThat(includeById.apply(id.apply(deleted))).isPresent();
    }

    private void markDeleted(Object entity) {
        Instant deletedAt = Instant.parse("2025-01-01T00:00:00Z");
        if (entity instanceof Account value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof Campaign value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof Contact value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof Lead value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof Opportunity value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof Task value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof Email value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof Address value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof AccountContact value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof AccountOpportunity value) {
            value.setDeletedAt(deletedAt);
        } else if (entity instanceof ContactOpportunity value) {
            value.setDeletedAt(deletedAt);
        } else {
            throw new IllegalArgumentException("Unsupported soft-delete entity: " + entity.getClass());
        }
    }

    private Specification<Account> isLiveAccount() {
        return (root, query, builder) -> builder.isNull(root.get("deletedAt"));
    }

    private Specification<Campaign> isLiveCampaign() {
        return (root, query, builder) -> builder.isNull(root.get("deletedAt"));
    }

    private Specification<Contact> isLiveContact() {
        return (root, query, builder) -> builder.isNull(root.get("deletedAt"));
    }

    private Specification<Lead> isLiveLead() {
        return (root, query, builder) -> builder.isNull(root.get("deletedAt"));
    }

    private Specification<Opportunity> isLiveOpportunity() {
        return (root, query, builder) -> builder.isNull(root.get("deletedAt"));
    }

    private Specification<Task> isLiveTask() {
        return (root, query, builder) -> builder.conjunction();
    }
}
