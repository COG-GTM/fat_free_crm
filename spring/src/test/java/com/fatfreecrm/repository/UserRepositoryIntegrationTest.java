package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pins {@link UserRepository#findByLogin} to the Rails {@code find_for_database_authentication} query:
 * {@code lower(username) = ? OR lower(email) = ?}, first row by id wins.
 */
class UserRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "repo_login_";
    private static final String INSERT_USER = """
        INSERT INTO users (username, email, encrypted_password, password_salt, admin, confirmed_at,
                           sign_in_count, created_at, updated_at)
        VALUES (?, ?, 'hash', 'salt', false, now(), 0, now(), now())
        """;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void insertUsers() {
        cleanUp();
        jdbcTemplate.update(INSERT_USER, PREFIX + "Alpha", PREFIX + "alpha@example.com");
        jdbcTemplate.update(INSERT_USER, PREFIX + "beta", PREFIX + "Tie@Example.com");
        jdbcTemplate.update(INSERT_USER, PREFIX + "tie@example.com", PREFIX + "gamma@example.com");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE ?", PREFIX + "%");
    }

    @Test
    void matchesTheLowercasedUsernameOrEmailColumn() {
        assertThat(usernames(userRepository.findByLogin(PREFIX + "alpha", Limit.of(1))))
            .containsExactly(PREFIX + "Alpha");
        assertThat(usernames(userRepository.findByLogin(PREFIX + "alpha@example.com", Limit.of(1))))
            .containsExactly(PREFIX + "Alpha");
        assertThat(usernames(userRepository.findByLogin(PREFIX + "gamma@example.com", Limit.of(1))))
            .containsExactly(PREFIX + "tie@example.com");
    }

    @Test
    void expectsAnAlreadyNormalizedLoginAndNeverMatchesPartialValues() {
        assertThat(userRepository.findByLogin(PREFIX + "Alpha", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin(" " + PREFIX + "alpha", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin(PREFIX + "alph", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin(PREFIX + "%", Limit.of(1))).isEmpty();
        assertThat(userRepository.findByLogin("", Limit.of(1))).isEmpty();
    }

    @Test
    void returnsTheLowestIdFirstWhenOneUsersEmailEqualsAnotherUsersUsername() {
        List<User> all = userRepository.findByLogin(PREFIX + "tie@example.com", Limit.unlimited());

        assertThat(usernames(all)).containsExactly(PREFIX + "beta", PREFIX + "tie@example.com");
        assertThat(all.get(0).getId()).isLessThan(all.get(1).getId());
        assertThat(usernames(userRepository.findByLogin(PREFIX + "tie@example.com", Limit.of(1))))
            .containsExactly(PREFIX + "beta");
    }

    @Test
    void locksAnExistingRowForUpdateAndReturnsEmptyForUnknownIds() {
        long id = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE username = ?", Long.class, PREFIX + "Alpha");

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(userRepository.findByIdForUpdate(id)).map(User::getUsername).contains(PREFIX + "Alpha");
            assertThat(userRepository.findByIdForUpdate(id + 1_000_000L)).isEmpty();
        });
    }

    private static List<String> usernames(List<User> users) {
        return users.stream().map(User::getUsername).toList();
    }
}
