package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * AB-273 exports: the export handlers sit on the authenticated list paths, so every negotiation style
 * (Accept, {@code ?format=}, Rails suffix) must reject anonymous requests and apply the same
 * {@code my(current_user)} scope as the JSON lists — another user's Private records never leak into
 * a CSV or XLS download.
 */
class ExportsAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        account(alice, "Alice Private Co", "Private");
        account(bob, "Bob Public Co", "Public");
        account(bob, "Bob Private Co", "Private");
        account(bob, "Bob Shared Co", "Shared");
        task(alice, "Alice call");
        task(bob, "Bob secret call");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void anonymousExportsAreUnauthorizedForEveryNegotiationStyle() throws Exception {
        for (String family : List.of("accounts", "campaigns", "contacts", "leads", "opportunities", "tasks",
                "activities")) {
            String path = "/api/v1/" + family;
            mockMvc.perform(get(path + ".csv")).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path + ".xls")).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).param("format", "csv")).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header(HttpHeaders.ACCEPT, "text/csv"))
                .andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header(HttpHeaders.ACCEPT, "application/vnd.ms-excel"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void invalidBearerTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/accounts.csv").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void accountExportsApplyTheMyScopeForEveryNegotiationStyle() throws Exception {
        List<MockHttpServletRequestBuilder> styles = List.of(
            get("/api/v1/accounts.csv"),
            get("/api/v1/accounts").param("format", "csv"),
            get("/api/v1/accounts").header(HttpHeaders.ACCEPT, "text/csv"),
            get("/api/v1/accounts.xls"),
            get("/api/v1/accounts").param("format", "xls"),
            get("/api/v1/accounts").header(HttpHeaders.ACCEPT, "application/vnd.ms-excel"));
        for (MockHttpServletRequestBuilder style : styles) {
            String body = mockMvc.perform(style.header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).contains("Alice Private Co").contains("Bob Public Co");
            assertThat(body).doesNotContain("Bob Private Co").doesNotContain("Bob Shared Co");
        }
    }

    @Test
    void taskExportsOnlyIncludeTheCurrentUsersTasks() throws Exception {
        for (String path : List.of("/api/v1/tasks.csv", "/api/v1/tasks.xls")) {
            String body = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).as(path).contains("Alice call").doesNotContain("Bob secret call");
        }
    }

    @Test
    void sensitiveUserColumnsNeverAppearInExports() throws Exception {
        String csv = mockMvc.perform(get("/api/v1/accounts.csv").header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv.lines().findFirst().orElse("")).doesNotContainIgnoringCase("password")
            .doesNotContainIgnoringCase("token");
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private Account account(User owner, String name, String access) {
        Account account = new Account();
        account.setName(name);
        account.setAccess(access);
        account.setUser(owner);
        Instant at = Instant.now();
        account.setCreatedAt(at);
        account.setUpdatedAt(at);
        return accountRepository.saveAndFlush(account);
    }

    private void task(User owner, String name) {
        jdbcTemplate.update("INSERT INTO tasks (user_id, name, bucket, created_at, updated_at)"
            + " VALUES (?, ?, 'due_asap', now(), now())", owner.getId(), name);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM tasks");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
