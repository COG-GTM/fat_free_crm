package com.fatfreecrm.service;

import com.fatfreecrm.domain.Group;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.Access;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.PermissionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared-access writes, mirroring {@code FatFreeCRM::Permissions} ({@code lib/fat_free_crm/permissions.rb}).
 *
 * <ul>
 *   <li>{@link #setAccess}: any value other than {@code Shared} deletes every permission row of the asset.</li>
 *   <li>{@link #setUserIds}/{@link #setGroupIds}: when access is not {@code Shared} they delete every row;
 *       otherwise ids are flattened, blanks dropped, de-duplicated and converted like Ruby {@code to_i}; rows
 *       of that kind not in the new list are deleted and missing ones inserted. Rows of the other kind stay.</li>
 * </ul>
 * Rows carry the Rails base class name in {@code asset_type} and equal {@code created_at}/{@code updated_at}.
 */
@Service
@Transactional
public class PermissionService {

    private static final Pattern RUBY_INTEGER = Pattern.compile("^\\s*([+-]?\\d+)");

    private final PermissionRepository permissionRepository;
    private final EntityManager entityManager;

    public PermissionService(PermissionRepository permissionRepository, EntityManager entityManager) {
        this.permissionRepository = permissionRepository;
        this.entityManager = entityManager;
    }

    /** Applies {@code access}, {@code user_ids} and {@code group_ids} in Rails attribute order; nulls are skipped. */
    public void updateSharing(CrmEntity entity, String access, Collection<?> userIds, Collection<?> groupIds) {
        CrmEntity managed = managed(entity);
        if (access != null) {
            applyAccess(entity, managed, access);
        }
        if (userIds != null) {
            replaceUsers(managed, userIds);
        }
        if (groupIds != null) {
            replaceGroups(managed, groupIds);
        }
    }

    public void setAccess(CrmEntity entity, String access) {
        applyAccess(entity, managed(entity), access);
    }

    public void setUserIds(CrmEntity entity, Collection<?> userIds) {
        replaceUsers(managed(entity), userIds);
    }

    public void setGroupIds(CrmEntity entity, Collection<?> groupIds) {
        replaceGroups(managed(entity), groupIds);
    }

    private void applyAccess(CrmEntity entity, CrmEntity managed, String access) {
        if (!Access.SHARED.railsValue().equals(access)) {
            removePermissions(managed);
        }
        managed.setAccess(access);
        entity.setAccess(access);
    }

    private void replaceUsers(CrmEntity managed, Collection<?> userIds) {
        replace(managed, userIds, Permission::getUser, User::getId, (permission, id) ->
            permission.setUser(entityManager.getReference(User.class, id)));
    }

    private void replaceGroups(CrmEntity managed, Collection<?> groupIds) {
        replace(managed, groupIds, Permission::getGroup, Group::getId, (permission, id) ->
            permission.setGroup(entityManager.getReference(Group.class, id)));
    }

    /** Ruby {@code value.flatten.reject(&:blank?).uniq.map(&:to_i)}. */
    static List<Long> normalizeIds(Collection<?> values) {
        List<Object> flat = new ArrayList<>();
        flatten(values, flat);
        Set<Object> unique = new LinkedHashSet<>();
        for (Object value : flat) {
            if (value != null && !(value instanceof CharSequence text && text.toString().isBlank())) {
                unique.add(value);
            }
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object value : unique) {
            ids.add(rubyToI(value));
        }
        return List.copyOf(ids);
    }

    private <R> void replace(
        CrmEntity entity,
        Collection<?> values,
        Function<Permission, R> reference,
        Function<R, Long> referenceId,
        java.util.function.BiConsumer<Permission, Long> assign
    ) {
        if (!Access.SHARED.railsValue().equals(entity.getAccess())) {
            removePermissions(entity);
            return;
        }
        List<Long> wanted = normalizeIds(values);
        List<Long> current = new ArrayList<>();
        List<Permission> stale = new ArrayList<>();
        for (Permission permission : rowsFor(entity)) {
            R target = reference.apply(permission);
            if (target == null) {
                continue;
            }
            Long id = referenceId.apply(target);
            current.add(id);
            if (!wanted.contains(id)) {
                stale.add(permission);
            }
        }
        permissionRepository.deleteAll(stale);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (Long id : wanted) {
            if (!current.contains(id)) {
                Permission permission = new Permission();
                assign.accept(permission, id);
                permission.setAssetType(assetType(entity));
                permission.setAssetId(Math.toIntExact(entity.getId()));
                permission.setCreatedAt(now);
                permission.setUpdatedAt(now);
                permissionRepository.save(permission);
            }
        }
        permissionRepository.flush();
    }

    private void removePermissions(CrmEntity entity) {
        if (entity.getId() == null) {
            return;
        }
        permissionRepository.deleteAll(rowsFor(entity));
        permissionRepository.flush();
    }

    private List<Permission> rowsFor(CrmEntity entity) {
        return permissionRepository.findByAssetTypeAndAssetId(assetType(entity), Math.toIntExact(entity.getId()));
    }

    private CrmEntity managed(CrmEntity entity) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(entity.getId(), "entity must be persisted before permissions are written");
        if (entityManager.contains(entity)) {
            return entity;
        }
        // Load the current row instead of merging, so stale fields on a detached instance are never written back.
        Object current = entityManager.find(Hibernate.getClass(entity), entity.getId());
        if (current == null) {
            throw new EntityNotFoundException(Hibernate.getClass(entity).getSimpleName() + " " + entity.getId());
        }
        return (CrmEntity) current;
    }

    private static String assetType(CrmEntity entity) {
        Class<?> type = Hibernate.getClass(entity);
        for (RailsModelType candidate : RailsModelType.values()) {
            if (candidate.entityClass() == type) {
                return candidate.railsName();
            }
        }
        throw new IllegalArgumentException("No Rails model for " + type.getName());
    }

    private static void flatten(Collection<?> values, List<Object> into) {
        for (Object value : values) {
            if (value instanceof Collection<?> nested) {
                flatten(nested, into);
            } else {
                into.add(value);
            }
        }
    }

    private static long rubyToI(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        Matcher matcher = RUBY_INTEGER.matcher(value.toString().replace("_", ""));
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }
}
