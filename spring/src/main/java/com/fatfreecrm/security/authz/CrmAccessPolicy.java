package com.fatfreecrm.security.authz;

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
import com.fatfreecrm.domain.support.Access;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Reproduces {@code Ability} ({@code app/models/users/ability.rb}) as SQL predicates, i.e. what
 * {@code Klass.accessible_by(user.ability)} / {@code scope :my} generate in Rails.
 *
 * <p>Admin state is reloaded from the database on every call, never taken from the JWT. Permission and
 * group checks are {@code EXISTS}/{@code IN} subqueries, so list counts never fan out.
 */
@Component
public class CrmAccessPolicy implements AccessPolicy {

    private static final Map<Class<?>, RailsModelType> PERMISSIONED_ENTITIES = Map.of(
        Account.class, RailsModelType.ACCOUNT,
        Campaign.class, RailsModelType.CAMPAIGN,
        Contact.class, RailsModelType.CONTACT,
        Lead.class, RailsModelType.LEAD,
        Opportunity.class, RailsModelType.OPPORTUNITY
    );

    private final UserRepository userRepository;

    public CrmAccessPolicy(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Entity types with a row-level rule; any other type is rejected by {@link #accessibleBy}. */
    public static boolean supports(Class<?> entityType) {
        return PERMISSIONED_ENTITIES.containsKey(entityType)
            || entityType == Task.class
            || entityType == Comment.class
            || entityType == Email.class
            || entityType == User.class;
    }

    @Override
    public <T> Specification<T> accessibleBy(AuthenticatedUser user, Class<T> entityType) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(entityType, "entityType");
        if (!supports(entityType)) {
            throw new IllegalArgumentException("No access rule for entity type " + entityType.getName());
        }
        Long userId = Objects.requireNonNull(user.id(), "user.id");
        Boolean admin = userRepository.findById(userId).map(User::isAdmin).orElse(null);
        if (admin == null) {
            return (root, query, cb) -> cb.disjunction();
        }
        if (admin) {
            return (root, query, cb) -> cb.conjunction();
        }
        RailsModelType permissionedType = PERMISSIONED_ENTITIES.get(entityType);
        if (permissionedType != null) {
            return (root, query, cb) -> entityPredicate(root, query, cb, userId, permissionedType);
        }
        if (entityType == Task.class) {
            return (root, query, cb) -> cb.or(
                cb.equal(root.get("user").get("id"), userId),
                cb.equal(root.get("assignedTo").get("id"), userId),
                cb.equal(root.get("completedBy").get("id"), userId)
            );
        }
        if (entityType == User.class) {
            return (root, query, cb) -> cb.equal(root.get("id"), userId);
        }
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    private static Predicate entityPredicate(
        Root<?> root,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Long userId,
        RailsModelType assetType
    ) {
        // Rails reads user.group_ids through the groups table, so join rows of destroyed groups never count.
        Subquery<Long> myGroups = query.subquery(Long.class);
        Root<Group> group = myGroups.from(Group.class);
        Join<Group, User> member = group.join("users");
        myGroups.select(group.get("id")).where(cb.equal(member.get("id"), userId));

        Subquery<Long> grant = query.subquery(Long.class);
        Root<Permission> permission = grant.from(Permission.class);
        grant.select(permission.get("id")).where(
            cb.equal(permission.get("assetType"), assetType.railsName()),
            cb.equal(permission.get("assetId"), root.get("id")),
            cb.or(
                cb.equal(permission.get("user").get("id"), userId),
                permission.get("group").get("id").in(myGroups)
            )
        );

        return cb.or(
            cb.equal(root.get("access"), Access.PUBLIC.railsValue()),
            cb.equal(root.get("user").get("id"), userId),
            cb.equal(root.get("assignedTo").get("id"), userId),
            cb.exists(grant)
        );
    }
}
