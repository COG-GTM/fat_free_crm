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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * Committed-transaction coverage for {@code GET /api/v1/accounts}: {@code Tagging.tag} is LAZY and
 * there is no open-in-view, so {@code tag_list} must be loaded eagerly in the repository query.
 */
class AccountsControllerNoTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

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

    @AfterEach
    void cleanUp() {
        taggingRepository.deleteAll();
        tagRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void tagListLoadsOutsideTheRepositoryCall() throws Exception {
        User alice = new User();
        alice.setUsername("lazy_alice");
        alice.setEncryptedPassword("enc");
        alice.setPasswordSalt("salt");
        alice.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        userRepository.saveAndFlush(alice);

        Account account = new Account();
        account.setName("Lazy Tagged Corp");
        account.setAccess("Public");
        account.setUser(alice);
        Instant at = Instant.parse("2025-01-03T00:00:00Z");
        account.setCreatedAt(at);
        account.setUpdatedAt(at);
        accountRepository.saveAndFlush(account);

        Tag tag = new Tag();
        tag.setName("lazyvip");
        tagRepository.saveAndFlush(tag);
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Account");
        tagging.setTaggableId(account.getId().intValue());
        tagging.setContext("tags");
        taggingRepository.saveAndFlush(tagging);

        mockMvc.perform(get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenService.issue(alice).accessToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].tag_list[0]").value("lazyvip"));
    }
}
