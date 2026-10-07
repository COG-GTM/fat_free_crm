package com.fatfreecrm.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/** Throwaway {@code GET /api/v1/accounts}: auth, envelope shape, snake_case DTO fields, tag_list. */
@Transactional
class AccountsControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private TaggingRepository taggingRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private String bearer;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");

        User alice = new User();
        alice.setUsername("alice");
        alice.setEncryptedPassword("enc");
        alice.setPasswordSalt("salt");
        alice.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        userRepository.save(alice);
        bearer = "Bearer " + jwtTokenService.issue(alice).accessToken();

        Account account = new Account();
        account.setName("Acme Corp");
        account.setEmail("acme@example.com");
        account.setCategory("customer");
        account.setRating(5);
        account.setAccess("Public");
        account.setUser(alice);
        Instant at = Instant.parse("2025-01-03T00:00:00Z");
        account.setCreatedAt(at);
        account.setUpdatedAt(at);
        accountRepository.save(account);

        Tag tag = new Tag();
        tag.setName("vip");
        tagRepository.save(tag);
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Account");
        tagging.setTaggableId(account.getId().intValue());
        tagging.setContext("tags");
        taggingRepository.save(tagging);
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsListEnvelopeWithSnakeCaseFields() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.perPage").value(20))
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Acme Corp"))
            .andExpect(jsonPath("$.items[0].user_id").isNumber())
            .andExpect(jsonPath("$.items[0].created_at").value("2025-01-03T00:00:00.000Z"))
            .andExpect(jsonPath("$.items[0].tag_list[0]").value("vip"))
            .andExpect(jsonPath("$.facets.category.all").value(1))
            .andReturn();
        org.assertj.core.api.Assertions.assertThat(
            result.getResponse().getContentAsString()).contains("\"perPage\"");
    }

    @Test
    void invalidPageReturns404Problem() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("page", "0").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }
}
