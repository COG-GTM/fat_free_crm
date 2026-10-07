package com.fatfreecrm.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Task;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BaseEntityEqualityTest {

    @Test
    void transientEntitiesOnlyEqualThemselves() {
        Account first = new Account();
        Account second = new Account();

        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo(second);
        assertThat(first).isNotEqualTo(null);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void persistedEntitiesEqualByEntityClassAndIdentifier() throws ReflectiveOperationException {
        Account account = withId(new Account(), 1L);
        Account sameRow = withId(new Account(), 1L);
        Account otherRow = withId(new Account(), 2L);
        Contact contactWithSameId = withId(new Contact(), 1L);

        assertThat(account).isEqualTo(sameRow);
        assertThat(sameRow).isEqualTo(account);
        assertThat(account.hashCode()).isEqualTo(sameRow.hashCode());
        assertThat(account).isNotEqualTo(otherRow);
        assertThat(account).isNotEqualTo(contactWithSameId);
        assertThat(contactWithSameId).isNotEqualTo(account);
    }

    @Test
    void transientEntityNeverEqualsPersistedEntity() throws ReflectiveOperationException {
        Task unsaved = new Task();
        Task saved = withId(new Task(), 1L);

        assertThat(unsaved).isNotEqualTo(saved);
        assertThat(saved).isNotEqualTo(unsaved);
    }

    @Test
    void hashCodeIsStableAcrossIdAssignmentSoSetMembershipSurvivesPersist() throws ReflectiveOperationException {
        Account account = new Account();
        Set<Account> accounts = new HashSet<>();
        accounts.add(account);
        int beforePersist = account.hashCode();

        withId(account, 42L);

        assertThat(account.hashCode()).isEqualTo(beforePersist);
        assertThat(accounts).contains(account);
        assertThat(accounts).contains(withId(new Account(), 42L));
    }

    private static <T extends BaseEntity> T withId(T entity, Long id) throws ReflectiveOperationException {
        Field idField = BaseEntity.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
        return entity;
    }
}
