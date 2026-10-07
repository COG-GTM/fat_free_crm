package com.fatfreecrm.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Guards of {@link CrmAccessPolicy} that do not need a database: null handling, the closed set of entity types
 * with a CanCanCan rule, and that the admin decision comes from {@code users.admin}, never from the token.
 */
class CrmAccessPolicyTest {

    private static final Set<Class<?>> RULED_TYPES = Set.of(
        Account.class, Campaign.class, Contact.class, Lead.class, Opportunity.class,
        Task.class, Comment.class, Email.class, User.class);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CrmAccessPolicy policy = new CrmAccessPolicy(userRepository);
    private final AuthenticatedUser user = new AuthenticatedUser(7L, "seven", false);

    @Test
    void supportsExactlyTheEntitiesTheRailsAbilityDefinesRulesFor() {
        for (RailsModelType type : RailsModelType.values()) {
            assertThat(CrmAccessPolicy.supports(type.entityClass()))
                .as(type.railsName()).isEqualTo(RULED_TYPES.contains(type.entityClass()));
        }
        assertThat(CrmAccessPolicy.supports(Object.class)).isFalse();
    }

    @Test
    void rejectsNullArgumentsAndUsersWithoutAnId() {
        assertThatThrownBy(() -> policy.accessibleBy(null, Account.class)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.accessibleBy(user, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.accessibleBy(new AuthenticatedUser(null, "anonymous", true), Account.class))
            .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsTypesWithoutARuleBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> policy.accessibleBy(user, Permission.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Permission.class.getName());
        assertThatThrownBy(() -> policy.accessibleBy(user, Group.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.accessibleBy(user, Object.class)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void unknownUsersSeeNothingEvenWhenTheTokenClaimsAdmin() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate nothing = mock(Predicate.class);
        when(cb.disjunction()).thenReturn(nothing);

        Specification<Account> spec = policy.accessibleBy(new AuthenticatedUser(404L, "ghost", true), Account.class);

        assertThat(spec.toPredicate(null, null, cb)).isSameAs(nothing);
        verify(userRepository).findById(404L);
    }

    @Test
    void adminDecisionComesFromTheUsersTableNotTheToken() {
        User admin = new User();
        admin.setAdmin(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(admin));
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate everything = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(everything);

        Specification<Task> spec = policy.accessibleBy(user, Task.class);

        assertThat(spec.toPredicate(null, null, cb)).isSameAs(everything);
    }
}
