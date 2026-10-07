package com.fatfreecrm.security.authz;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Rails {@code Admin::ApplicationController#require_admin_user}. {@code ROLE_ADMIN} is derived from the
 * {@code users.admin} column on every request by {@code FfcrmJwtAuthenticationConverter}, never from a claim.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@PreAuthorize("hasRole('ADMIN')")
public @interface AdminOnly {
}
