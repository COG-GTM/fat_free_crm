package com.fatfreecrm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.support.TestDataSeeder.AccountRow;
import com.fatfreecrm.support.TestDataSeeder.ContactRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** The application boots against PostgreSQL with {@code ddl-auto=none} and can query the tables. */
class ContextLoadsTest extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Test
    void contextLoadsWithoutHibernateSchemaManagement() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        assertThat(accountRepository.count()).isZero();
        assertThat(contactRepository.count()).isZero();
    }

    @Test
    void entitiesMapEveryColumnOfTheRailsTables() {
        seeder.insertUser(1, "alice", false);
        seeder.insertAccount(AccountRow.minimal(10, "Acme", 1L));
        seeder.insertContact(ContactRow.minimal(20, "Bob", "Builder", 1L));

        assertThat(accountRepository.findById(10L)).hasValueSatisfying(a -> {
            assertThat(a.getName()).isEqualTo("Acme");
            assertThat(a.getUserId()).isEqualTo(1L);
            assertThat(a.getAccess()).isEqualTo("Public");
            assertThat(a.getRating()).isZero();
            assertThat(a.getSubscribedUsers()).isNull();
        });
        assertThat(contactRepository.findById(20L)).hasValueSatisfying(c -> {
            assertThat(c.getFirstName()).isEqualTo("Bob");
            assertThat(c.getLastName()).isEqualTo("Builder");
            assertThat(c.getDoNotCall()).isFalse();
        });
    }
}
