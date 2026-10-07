package com.fatfreecrm.security.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.FfcrmAuthenticationToken;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The deny-by-default paths of {@link CrmPermissionEvaluator}: anything that is not a CRM user with a
 * well-formed target and a Rails {@code :manage}-covered action is refused before any repository is consulted.
 */
class CrmPermissionEvaluatorTest {

    private final AccessPolicy accessPolicy = mock(AccessPolicy.class);
    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final CrmPermissionEvaluator evaluator = new CrmPermissionEvaluator(accessPolicy, applicationContext);

    @Test
    void principalsThatAreNotCrmUsersAreDeniedEvenWithAdminRole() {
        Authentication[] foreign = {
            new UsernamePasswordAuthenticationToken("admin", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")),
            new TestingAuthenticationToken("admin", "n/a", "ROLE_ADMIN", "ROLE_USER"),
            null
        };
        for (Authentication authentication : foreign) {
            assertThat(evaluator.hasPermission(authentication, 1L, "Account", "read")).isFalse();
            assertThat(evaluator.hasPermission(authentication, new Account(), "read")).isFalse();
        }
        verifyNoInteractions(accessPolicy, applicationContext);
    }

    @Test
    void targetTypesWithoutARailsRuleAreDenied() {
        FfcrmAuthenticationToken admin = token(true);
        for (String type : new String[] {"Permission", "Group", "Setting", "account", "NoSuchModel", "", null}) {
            assertThat(evaluator.hasPermission(admin, 1L, type, "read")).as(type).isFalse();
        }
        verifyNoInteractions(accessPolicy, applicationContext);
    }

    @Test
    void onlyRailsManageActionsAreRecognised() {
        FfcrmAuthenticationToken admin = token(true);
        for (Object permission : new Object[] {"create", "index", "fly", "READ", "", 42, null, new Object()}) {
            assertThat(evaluator.hasPermission(admin, 1L, "Account", permission)).as(String.valueOf(permission))
                .isFalse();
        }
        verifyNoInteractions(accessPolicy, applicationContext);
    }

    @Test
    void targetIdsThatAreNotIntegersAreDenied() {
        FfcrmAuthenticationToken admin = token(true);
        Serializable[] ids = {"", " ", "abc", "1.5", "1e3", "0x1A", "1_000", UUID.randomUUID(), LocalDate.EPOCH, null};
        for (Serializable id : ids) {
            assertThat(evaluator.hasPermission(admin, id, "Account", "read")).as(String.valueOf(id)).isFalse();
        }
        verifyNoInteractions(accessPolicy, applicationContext);
    }

    @Test
    void domainObjectsWithoutAPersistentIdAreDenied() {
        FfcrmAuthenticationToken admin = token(true);
        assertThat(evaluator.hasPermission(admin, null, "read")).isFalse();
        assertThat(evaluator.hasPermission(admin, new Account(), "read")).isFalse();
        assertThat(evaluator.hasPermission(admin, "not-an-entity", "read")).isFalse();
        assertThat(evaluator.hasPermission(admin, new Object(), "read")).isFalse();
        verifyNoInteractions(accessPolicy, applicationContext);
    }

    private static FfcrmAuthenticationToken token(boolean admin) {
        AuthenticatedUser user = new AuthenticatedUser(1L, "someone", admin);
        Jwt jwt = Jwt.withTokenValue("unit").header("alg", "none").subject("1").build();
        return new FfcrmAuthenticationToken(
            jwt, AuthorityUtils.createAuthorityList(admin ? "ROLE_ADMIN" : "ROLE_USER"), user);
    }
}
