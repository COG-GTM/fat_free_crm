package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AB-272 review: subscribe/unsubscribe on an entity that fails Rails validations must persist
 * nothing — the mutation used to stay dirty on the managed entity, so the surrounding commit
 * flushed {@code subscribed_users} anyway. Rails' {@code entity.save} fails validation and
 * leaves the row untouched.
 */
class EntitySubscribeUnsaveableIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private String aliceBearer;
    private long accountId;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        accountId = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, created_at, updated_at)"
                + " VALUES ('', ?, 'Public', now(), now()) RETURNING id",
            Long.class, alice.getId());
    }

    @AfterEach
    void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void subscribeOnUnsaveableEntityPersistsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/subscribe")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isInternalServerError());
        assertThat(subscribedUsers()).isNull();
    }

    @Test
    void unsubscribeOnUnsaveableEntityPersistsNothing() throws Exception {
        jdbcTemplate.update("UPDATE accounts SET subscribed_users = ? WHERE id = ?",
            "---\n- " + alice.getId() + "\n", accountId);
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/unsubscribe")
                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors.name.length()").value(1))
            .andExpect(jsonPath("$.errors.name[0]").value("^Please specify account name."));
        assertThat(subscribedUsers()).isEqualTo("---\n- " + alice.getId() + "\n");
    }

    private String subscribedUsers() {
        return jdbcTemplate.queryForObject(
            "SELECT subscribed_users FROM accounts WHERE id = ?", String.class, accountId);
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }
}
