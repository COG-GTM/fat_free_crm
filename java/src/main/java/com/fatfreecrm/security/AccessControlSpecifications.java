package com.fatfreecrm.security;

import com.fatfreecrm.domain.CrmEntity;
import com.fatfreecrm.domain.Permission;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * JPA Specifications reproducing the Rails record-visibility rules ({@code Model.my(user)} /
 * {@code Ability}, see app/models/users/ability.rb and lib/fat_free_crm/permissions.rb).
 *
 * <p>A non-admin user sees a record when any of the following holds:
 * <pre>
 *   access = 'Public'
 *   OR user_id = :me
 *   OR assigned_to = :me
 *   OR EXISTS (SELECT 1 FROM permissions p
 *               WHERE p.asset_type = :assetType AND p.asset_id = root.id
 *                 AND (p.user_id = :me OR p.group_id IN (:myGroupIds)))
 * </pre>
 * Admins ({@code users.admin = true}) see everything. Soft-deleted rows are already excluded by
 * the {@code @SQLRestriction} on each entity.
 */
public final class AccessControlSpecifications {

    public static final String ACCESS_PUBLIC = "Public";
    public static final String ACCESS_PRIVATE = "Private";
    public static final String ACCESS_SHARED = "Shared";

    private AccessControlSpecifications() {
    }

    /**
     * @param user      the current user
     * @param assetType Rails class name stored in {@code permissions.asset_type}
     *                  (e.g. {@code "Account"}, {@code "Contact"})
     * @param <T>       entity type
     * @return a Specification restricting {@code T} rows to those visible to {@code user}
     */
    public static <T extends CrmEntity> Specification<T> visibleTo(CurrentUser user, String assetType) {
        if (user.admin()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            Predicate isPublic = cb.equal(root.get("access"), ACCESS_PUBLIC);
            Predicate isOwner = cb.equal(root.get("userId"), user.id());
            Predicate isAssignee = cb.equal(root.get("assignedTo"), user.id());
            Predicate isShared = cb.exists(permissionSubquery(root, query, cb, user, assetType));
            return cb.or(isPublic, isOwner, isAssignee, isShared);
        };
    }

    private static <T extends CrmEntity> Subquery<Integer> permissionSubquery(
            Root<T> root,
            CriteriaQuery<?> query,
            CriteriaBuilder cb,
            CurrentUser user,
            String assetType) {
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<Permission> p = sub.from(Permission.class);
        sub.select(cb.literal(1));

        Predicate grantee = cb.equal(p.get("userId"), user.id());
        if (!user.groupIds().isEmpty()) {
            grantee = cb.or(grantee, p.get("groupId").in(user.groupIds()));
        }
        sub.where(
                cb.equal(p.get("assetType"), assetType),
                cb.equal(p.get("assetId"), root.get("id")),
                grantee);
        return sub;
    }
}
