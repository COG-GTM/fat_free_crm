package com.fatfreecrm.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

/**
 * Pins the rule selection of {@link CrmAccessPolicy} to {@code app/models/users/ability.rb} without a database:
 * which entity types have a rule, that the admin flag is read from {@code users.admin} and not from the token,
 * and which columns each rule inspects.
 */
class CrmAccessPolicyTest {

    private static final long USER_ID = 7L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CrmAccessPolicy policy = new CrmAccessPolicy(userRepository);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);

    @BeforeEach
    void stubPredicates() {
        when(cb.equal(any(Expression.class), any(Object.class))).thenAnswer(invocation -> mock(Predicate.class));
        when(cb.equal(any(Expression.class), any(Expression.class))).thenAnswer(invocation -> mock(Predicate.class));
        when(cb.or(any(Predicate[].class))).thenAnswer(invocation -> mock(Predicate.class));
        when(cb.exists(any())).thenAnswer(invocation -> mock(Predicate.class));
        when(cb.conjunction()).thenAnswer(invocation -> mock(Predicate.class));
        when(cb.disjunction()).thenAnswer(invocation -> mock(Predicate.class));
    }

    @Test
    void supportsExactlyTheTypesAbilityDefinesRulesFor() {
        assertThat(CrmAccessPolicy.supports(Account.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(Campaign.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(Contact.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(Lead.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(Opportunity.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(Task.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(Comment.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(Email.class)).isTrue();
        assertThat(CrmAccessPolicy.supports(User.class)).isTrue();

        assertThat(CrmAccessPolicy.supports(Permission.class)).isFalse();
        assertThat(CrmAccessPolicy.supports(Group.class)).isFalse();
        assertThat(CrmAccessPolicy.supports(Setting.class)).isFalse();
        assertThat(CrmAccessPolicy.supports(Object.class)).isFalse();
    }

    @Test
    void rejectsMissingUserTypeOrUserIdBeforeQueryingTheDatabase() {
        assertThatThrownBy(() -> policy.accessibleBy(null, Account.class)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.accessibleBy(user(false), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.accessibleBy(new AuthenticatedUser(null, "no-id", false), Account.class))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.accessibleBy(user(true), Group.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Group.class.getName());
        verifyNoInteractions(userRepository);
    }

    @Test
    void unknownUserIdGetsAnAlwaysFalsePredicate() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        toPredicate(policy.accessibleBy(user(true), Account.class), Account.class);

        verify(cb).disjunction();
        verify(cb, never()).conjunction();
        verify(cb, never()).or(any(Predicate[].class));
    }

    @Test
    void adminFlagIsReadFromTheUsersTableNotFromTheToken() {
        stubUser(true);
        toPredicate(policy.accessibleBy(user(false), Account.class), Account.class);
        verify(cb).conjunction();
        verify(cb, never()).disjunction();
        verify(cb, never()).or(any(Predicate[].class));
    }

    @Test
    void forgedAdminClaimDoesNotWidenANonAdminsScope() {
        stubUser(false);
        Root<User> root = root();

        policy.accessibleBy(user(true), User.class).toPredicate(root, query, cb);

        verify(cb, never()).conjunction();
        verifyEqual(root.get("id"), USER_ID);
    }

    @Test
    void taskRuleChecksOwnerAssigneeAndCompleterAndIgnoresAccessAndPermissions() {
        stubUser(false);
        Root<Task> root = root();

        policy.accessibleBy(user(false), Task.class).toPredicate(root, query, cb);

        verifyEqual(root.get("user").get("id"), USER_ID);
        verifyEqual(root.get("assignedTo").get("id"), USER_ID);
        verifyEqual(root.get("completedBy").get("id"), USER_ID);
        verify(cb).or(any(Predicate.class), any(Predicate.class), any(Predicate.class));
        verify(root, never()).get("access");
        verify(query, never()).subquery(any());
    }

    @Test
    void commentAndEmailRulesCheckOnlyTheAuthor() {
        stubUser(false);
        for (Class<?> type : new Class<?>[] {Comment.class, Email.class}) {
            Root<Object> root = root();

            @SuppressWarnings("unchecked")
            Specification<Object> spec = (Specification<Object>) policy.accessibleBy(user(false), type);
            spec.toPredicate(root, query, cb);

            verifyEqual(root.get("user").get("id"), USER_ID);
            verify(root, never()).get("access");
            verify(root, never()).get("assignedTo");
        }
        verify(query, never()).subquery(any());
        verify(cb, never()).or(any(Predicate[].class));
    }

    @Test
    void entityRuleIsPublicOrOwnerOrAssigneeOrPermissionRowForThatAssetType() {
        stubUser(false);
        Root<Lead> root = root();

        policy.accessibleBy(user(false), Lead.class).toPredicate(root, query, cb);

        verifyEqual(root.get("access"), "Public");
        verifyEqual(root.get("user").get("id"), USER_ID);
        verifyEqual(root.get("assignedTo").get("id"), USER_ID);
        verify(cb).or(any(Predicate.class), any(Predicate.class), any(Predicate.class), any(Predicate.class));
        verify(cb).exists(any());
        var permission = query.subquery(Long.class).from(Permission.class);
        verifyEqual(permission.get("assetType"), "Lead");
        verifyEqual(permission.get("assetId"), root.get("id"));
        verifyEqual(permission.get("user").get("id"), USER_ID);
        verify(query.subquery(Long.class).from(Group.class)).join("users");
    }

    @Test
    void permissionSubqueryUsesTheRailsClassNameOfEachEntity() {
        stubUser(false);
        for (Class<?> type : new Class<?>[] {Account.class, Campaign.class, Contact.class, Opportunity.class}) {
            CriteriaBuilder builder = mock(CriteriaBuilder.class);
            CriteriaQuery<?> criteria = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
            Root<Object> root = root();
            when(builder.equal(any(Expression.class), any(Object.class)))
                .thenAnswer(invocation -> mock(Predicate.class));
            when(builder.equal(any(Expression.class), any(Expression.class)))
                .thenAnswer(invocation -> mock(Predicate.class));
            when(builder.or(any(Predicate[].class))).thenAnswer(invocation -> mock(Predicate.class));
            when(builder.exists(any())).thenAnswer(invocation -> mock(Predicate.class));

            @SuppressWarnings("unchecked")
            Specification<Object> spec = (Specification<Object>) policy.accessibleBy(user(false), type);
            spec.toPredicate(root, criteria, builder);

            Expression<?> assetType = criteria.subquery(Long.class).from(Permission.class).get("assetType");
            verify(builder).equal(assetType, type.getSimpleName());
        }
    }

    private void verifyEqual(Expression<?> left, Object right) {
        verify(cb).equal(left, right);
    }

    private void verifyEqual(Expression<?> left, Expression<?> right) {
        verify(cb).equal(left, right);
    }

    private void stubUser(boolean admin) {
        User user = new User();
        user.setAdmin(admin);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private static AuthenticatedUser user(boolean adminClaim) {
        return new AuthenticatedUser(USER_ID, "someone", adminClaim);
    }

    @SuppressWarnings("unchecked")
    private static <T> Root<T> root() {
        return (Root<T>) mock(Root.class, RETURNS_DEEP_STUBS);
    }

    @SuppressWarnings("unchecked")
    private <T> void toPredicate(Specification<?> spec, Class<T> type) {
        ((Specification<T>) spec).toPredicate(root(), query, cb);
    }
}
