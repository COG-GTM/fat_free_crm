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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/** Export endpoints are behind the same JWT gate and access scoping as the JSON lists. */
class ExportsAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final List<String> RESOURCES =
        List.of("accounts", "campaigns", "contacts", "leads", "opportunities", "tasks", "activities");

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

    @BeforeEach
    void seed() {
        clearData();
        alice = user("export-authz-alice");
        bob = user("export-authz-bob");
        account("Alice Public", alice, "Public");
        account("Alice Private", alice, "Private");
        account("Bob Private", bob, "Private");
        account("Bob Shared Unpermitted", bob, "Shared");
        account("Bob Public", bob, "Public");
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void anonymousExportRequestsAreRejectedWith401ForEveryStyleAndResource() throws Exception {
        for (String resource : RESOURCES) {
            mockMvc.perform(get("/api/v1/" + resource).header(HttpHeaders.ACCEPT, "text/csv"))
                .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/" + resource).header(HttpHeaders.ACCEPT, "application/vnd.ms-excel"))
                .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/" + resource + "?format=xls")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/" + resource + ".csv")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/" + resource + ".xls")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void garbageBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/accounts.csv").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void csvExportOnlyContainsRowsTheUserMayAccess() throws Exception {
        String csv = mockMvc.perform(get("/api/v1/accounts.csv").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("Alice Public", "Alice Private", "Bob Public");
        assertThat(csv).doesNotContain("Bob Private", "Bob Shared Unpermitted");
        assertThat(csv.lines().count()).isEqualTo(4);
    }

    @Test
    void xlsExportAppliesTheSameAccessScoping() throws Exception {
        String xls = mockMvc.perform(get("/api/v1/accounts.xls").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(xls).contains("Bob Private", "Bob Shared Unpermitted", "Bob Public", "Alice Public");
        assertThat(xls).doesNotContain("Alice Private");
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService.issue(user).accessToken();
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

    private Account account(String name, User owner, String access) {
        Account entity = new Account();
        entity.setName(name);
        entity.setAccess(access);
        entity.setUser(owner);
        return accountRepository.saveAndFlush(entity);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM permissions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
