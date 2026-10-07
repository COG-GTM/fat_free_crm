package com.fatfreecrm.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Permission;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import java.io.Serializable;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Fail-closed guards of {@link CrmPermissionEvaluator} that must deny before any repository or
 * {@link AccessPolicy} is consulted. Rails raises {@code CanCan::AccessDenied} for anything the
 * {@code Ability} does not explicitly grant, so every malformed input here must be a denial, never a grant.
 */
class CrmPermissionEvaluatorTest {

    private static final AuthenticatedUser USER = new AuthenticatedUser(7L, "seven", false);

    private final StaticApplicationContext applicationContext = new StaticApplicationContext();
    private final AccessPolicy neverConsulted = new AccessPolicy() {
        @Override
        public <T> org.springframework.data.jpa.domain.Specification<T> accessibleBy(
            AuthenticatedUser user, Class<T> entityType) {
            throw new AssertionError("AccessPolicy must not be consulted for a rejected request");
        }
    };
    private final CrmPermissionEvaluator evaluator = new CrmPermissionEvaluator(neverConsulted, applicationContext);

    CrmPermissionEvaluatorTest() {
        applicationContext.refresh();
    }

    @AfterEach
    void closeContext() {
        applicationContext.close();
    }

    @Test
    void deniesAuthenticationsThatDoNotCarryADatabaseUser() {
        Authentication password = new UsernamePasswordAuthenticationToken("seven", "secret",
            AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        Authentication testing = new TestingAuthenticationToken("seven", "secret", "ROLE_ADMIN");
        Authentication plainJwt = new JwtAuthenticationToken(jwt(), AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

        assertThat(evaluator.hasPermission(null, 1L, "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(password, 1L, "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(testing, 1L, "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(plainJwt, 1L, "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(password, account(1L), "read")).isFalse();
    }

    @Test
    void deniesPermissionNamesOutsideTheRailsManageAlias() {
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Account", "fly")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Account", "READ")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Account", "")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Account", null)).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Account", 1)).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), account(1L), "create")).isFalse();
    }

    @Test
    void deniesTargetTypesWithoutARowLevelRule() {
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Permission", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Group", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "Version", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "account", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, "NoSuchModel", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), 1L, null, "read")).isFalse();

        Permission permission = new Permission();
        assertThat(evaluator.hasPermission(ffcrm(), permission, "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), new IdOnly(5L), "read")).isFalse();
    }

    @Test
    void deniesTargetIdsThatAreNotIntegers() {
        assertThat(evaluator.hasPermission(ffcrm(), null, "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), "", "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), "abc", "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), "1.5", "Account", "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), UUID.randomUUID(), "Account", "read")).isFalse();
    }

    @Test
    void deniesDomainObjectsWithoutAnIdentifier() {
        assertThat(evaluator.hasPermission(ffcrm(), null, "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), new Object(), "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), new Account(), "read")).isFalse();
        assertThat(evaluator.hasPermission(ffcrm(), new IdOnly(null), "read")).isFalse();
    }

    @Test
    void failsInsteadOfGrantingWhenNoRepositoryExistsForTheType() {
        assertThatThrownBy(() -> evaluator.hasPermission(ffcrm(), 1L, "Account", "read"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(Account.class.getName());
        assertThatThrownBy(() -> evaluator.hasPermission(ffcrm(), " 1 ", "Account", "manage"))
            .isInstanceOf(IllegalStateException.class);
    }

    private static FfcrmAuthenticationToken ffcrm() {
        return new FfcrmAuthenticationToken(jwt(), AuthorityUtils.createAuthorityList("ROLE_USER"), USER);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("unit").header("alg", "none").subject(USER.id().toString()).build();
    }

    private static Account account(Long id) {
        Account account = new Account();
        account.setName("probe " + id);
        return account;
    }

    static final class IdOnly implements Serializable {

        private static final long serialVersionUID = 1L;

        private final Long id;

        IdOnly(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }
}
