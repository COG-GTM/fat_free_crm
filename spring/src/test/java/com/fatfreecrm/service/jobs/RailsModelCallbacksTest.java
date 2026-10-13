package com.fatfreecrm.service.jobs;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.mail.AssignmentNotificationService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RailsModelCallbacksTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AssignmentNotificationService assignmentNotifications = mock(AssignmentNotificationService.class);
    private final AccountEnrichmentTrigger accountEnrichment = mock(AccountEnrichmentTrigger.class);
    private final RailsModelCallbacks callbacks =
        new RailsModelCallbacks(userRepository, assignmentNotifications, accountEnrichment);

    @Test
    void resolvesStringAndUserWhodunnitValues() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        CrmEntity entity = mock(CrmEntity.class);

        callbacks.afterCreate(entity, RailsWhodunnit.string(" 7suffix"));
        callbacks.afterCreate(entity, RailsWhodunnit.user(user));

        verify(assignmentNotifications, times(2)).afterCreate(entity, user);
        verify(userRepository, times(2)).findById(7L);
    }

    @Test
    void integerAndNilWhodunnitValuesResolveToNil() {
        CrmEntity entity = mock(CrmEntity.class);

        callbacks.afterCreate(entity, RailsWhodunnit.integer(7));
        callbacks.afterCreate(entity, RailsWhodunnit.nil());

        verify(assignmentNotifications, org.mockito.Mockito.times(2)).afterCreate(entity, null);
        verify(userRepository, never()).findById(7L);
    }
}
