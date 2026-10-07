package com.fatfreecrm.security.authz;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import jakarta.persistence.EntityNotFoundException;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.Set;
import org.hibernate.Hibernate;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.support.Repositories;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-record checks for {@code @PreAuthorize("hasPermission(#id, 'Account', 'read')")}.
 *
 * <p>Evaluates {@code repository.exists(byId.and(accessibleBy))} with the same {@link AccessPolicy}
 * Specification used for lists, so list and fetch cannot disagree. Rails grants {@code :manage} for every
 * rule, so {@code read}, {@code update}, {@code destroy} and {@code manage} are equivalent. A target id
 * that does not exist raises {@link EntityNotFoundException} (404, as Rails' {@code find} does); an
 * existing row outside the user's scope is denied (403).
 */
@Component
public class CrmPermissionEvaluator implements PermissionEvaluator {

    private static final Set<String> ROW_PERMISSIONS = Set.of("read", "update", "destroy", "manage");

    private final AccessPolicy accessPolicy;
    private final ApplicationContext applicationContext;
    private volatile Repositories repositories;

    public CrmPermissionEvaluator(AccessPolicy accessPolicy, ApplicationContext applicationContext) {
        this.accessPolicy = accessPolicy;
        this.applicationContext = applicationContext;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (targetDomainObject == null) {
            return false;
        }
        Class<?> type = Hibernate.getClass(targetDomainObject);
        Long id = idOf(targetDomainObject);
        return id != null && check(authentication, id, type, permission);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPermission(
        Authentication authentication,
        Serializable targetId,
        String targetType,
        Object permission
    ) {
        Long id = toId(targetId);
        Optional<Class<?>> type = RailsModelType.fromRailsName(targetType).map(RailsModelType::entityClass);
        return id != null && type.isPresent() && check(authentication, id, type.get(), permission);
    }

    private <T> boolean check(Authentication authentication, Long id, Class<T> type, Object permission) {
        if (!(authentication instanceof FfcrmAuthenticationToken token)
            || !CrmAccessPolicy.supports(type)
            || !(permission instanceof String name)
            || !ROW_PERMISSIONS.contains(name)) {
            return false;
        }
        AuthenticatedUser user = token.getAuthenticatedUser();
        JpaSpecificationExecutor<T> executor = executorFor(type);
        Specification<T> byId = (root, query, cb) -> cb.equal(root.get("id"), id);
        if (executor.exists(byId.and(accessPolicy.accessibleBy(user, type)))) {
            return true;
        }
        if (!executor.exists(byId)) {
            throw new EntityNotFoundException(type.getSimpleName() + " " + id + " not found");
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <T> JpaSpecificationExecutor<T> executorFor(Class<T> type) {
        Repositories lookup = repositories;
        if (lookup == null) {
            lookup = new Repositories(applicationContext);
            repositories = lookup;
        }
        Object repository = lookup.getRepositoryFor(type)
            .orElseThrow(() -> new IllegalStateException("No repository for " + type.getName()));
        if (!(repository instanceof JpaSpecificationExecutor<?> executor)) {
            throw new IllegalStateException(type.getName() + " repository must extend JpaSpecificationExecutor");
        }
        return (JpaSpecificationExecutor<T>) executor;
    }

    private static Long idOf(Object entity) {
        try {
            Object id = entity.getClass().getMethod("getId").invoke(entity);
            return id instanceof Number number ? number.longValue() : null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            return null;
        }
    }

    private static Long toId(Serializable targetId) {
        if (targetId instanceof Number number) {
            return number.longValue();
        }
        if (targetId instanceof String text) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }
}
