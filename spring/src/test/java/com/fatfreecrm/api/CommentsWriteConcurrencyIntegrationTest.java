package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AB-272 review: concurrent comment creates on one commentable must both persist their
 * author-subscription append — the commentable row is locked FOR UPDATE before
 * {@code subscribed_users} is read (Rails' read-modify-write loses one append under the same load).
 */
class CommentsWriteConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private User bob;
    private String aliceBearer;
    private String bobBearer;
    private long accountId;

    @BeforeEach
    void seed() {
        clearData();
        alice = user("alice");
        bob = user("bob");
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        bobBearer = "Bearer " + jwtTokenService.issue(bob).accessToken();
        accountId = jdbcTemplate.queryForObject(
            "INSERT INTO accounts (name, user_id, access, created_at, updated_at)"
                + " VALUES ('Concurrency account', ?, 'Public', now(), now()) RETURNING id",
            Long.class, alice.getId());
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void parallelCommentCreatesPersistBothAuthorSubscriptions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = pool.submit(() -> postComment(aliceBearer, "first", ready, start));
            Future<Integer> second = pool.submit(() -> postComment(bobBearer, "second", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }

        List<Long> subscribed = jdbcTemplate.queryForList(
            "SELECT CAST(substring(line FROM 3) AS bigint) FROM"
                + " (SELECT unnest(string_to_array(subscribed_users, E'\\n')) AS line FROM accounts"
                + " WHERE id = ?) lines WHERE line ~ '^- [0-9]+$'",
            Long.class, accountId);
        assertThat(subscribed).containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    private int postComment(String bearer, String text, CountDownLatch ready, CountDownLatch start)
        throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return mockMvc.perform(post("/api/v1/comments")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":{\"commentable_type\":\"Account\",\"commentable_id\":"
                    + accountId + ",\"comment\":\"" + text + "\"}}"))
            .andReturn().getResponse().getStatus();
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");
    }
}
