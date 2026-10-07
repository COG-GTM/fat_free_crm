package com.fatfreecrm.security.authz;

import com.fatfreecrm.security.AuthenticatedUser;
import org.springframework.data.jpa.domain.Specification;

/**
 * Row-level access contract shared by authorization (AB-268) and search (AB-269).
 * List queries compose as {@code accessibleBy(user, type).and(search)}.
 */
public interface AccessPolicy {

    <T> Specification<T> accessibleBy(AuthenticatedUser user, Class<T> entityType);
}
