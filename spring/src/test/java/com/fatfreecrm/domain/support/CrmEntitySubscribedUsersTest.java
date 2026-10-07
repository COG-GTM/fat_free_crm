package com.fatfreecrm.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Task;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrmEntitySubscribedUsersTest {

    @Test
    void railsSubscribableModelsStartWithPublicAccessAndNoSubscribers() {
        for (CrmEntity entity : List.of(new Account(), new Campaign(), new Contact(), new Lead(), new Opportunity())) {
            assertThat(entity.getAccess()).isEqualTo("Public");
            assertThat(entity.accessLevel()).contains(Access.PUBLIC);
            assertThat(entity.getSubscribedUsers()).isEmpty();
            assertThat(entity.getUser()).isNull();
            assertThat(entity.getAssignedTo()).isNull();
            assertThat(entity.getDeletedAt()).isNull();
        }
        assertThat(CrmEntity.class.isAssignableFrom(Task.class)).isFalse();
        assertThat(new Task().getSubscribedUsers()).isEmpty();
    }

    @Test
    void setSubscribedUsersCopiesTheInputAndTreatsNullAsEmpty() {
        Account account = new Account();
        List<Long> source = new ArrayList<>(List.of(2L, 1L));

        account.setSubscribedUsers(source);
        source.add(9L);
        assertThat(account.getSubscribedUsers()).containsExactly(2L, 1L);

        account.setSubscribedUsers(null);
        assertThat(account.getSubscribedUsers()).isEmpty();

        account.setSubscribedUsers(List.of(1L));
        account.getSubscribedUsers().add(2L);
        assertThat(account.getSubscribedUsers()).containsExactly(1L, 2L);
    }

    @Test
    void taskSubscribedUsersFollowTheSameRules() {
        Task task = new Task();
        List<Long> source = new ArrayList<>(List.of(3L, 3L));

        task.setSubscribedUsers(source);
        source.clear();
        assertThat(task.getSubscribedUsers()).containsExactly(3L, 3L);

        task.setSubscribedUsers(null);
        assertThat(task.getSubscribedUsers()).isEmpty();
    }

    @Test
    void nullHydratedSubscribedUsersReadAsEmptyLists() throws ReflectiveOperationException {
        Lead lead = new Lead();
        nullOut(CrmEntity.class, lead);
        assertThat(lead.getSubscribedUsers()).isEmpty();

        Task task = new Task();
        nullOut(Task.class, task);
        assertThat(task.getSubscribedUsers()).isEmpty();
    }

    @Test
    void accessKeepsRawRailsStringsWhileAccessLevelOnlyMapsKnownNames() {
        Lead lead = new Lead();

        lead.setAccess("Campaign");
        assertThat(lead.getAccess()).isEqualTo("Campaign");
        assertThat(lead.accessLevel()).isEmpty();

        lead.setAccessLevel(Access.SHARED);
        assertThat(lead.getAccess()).isEqualTo("Shared");
        assertThat(lead.accessLevel()).contains(Access.SHARED);

        lead.setAccessLevel(null);
        assertThat(lead.getAccess()).isNull();
        assertThat(lead.accessLevel()).isEmpty();
    }

    private static void nullOut(Class<?> declaringClass, Object entity) throws ReflectiveOperationException {
        Field field = declaringClass.getDeclaredField("subscribedUsers");
        field.setAccessible(true);
        field.set(entity, null);
    }
}
