package com.fatfreecrm.service.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsBase64;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.service.read.UserPreferenceService.ListDefaults;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins {@link UserPreferenceService} to Rails {@code User#pref} semantics: preferences are
 * Base64-encoded JSON looked up per controller name, and {@code EntitiesController#set_options}
 * only honours positive integer per-page values and textual sort keys.
 */
@Transactional
class UserPreferenceServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UserPreferenceService service;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PreferenceRepository preferenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User user;
    private User otherUser;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM users");
        user = user("prefs_owner");
        otherUser = user("prefs_other");
    }

    @Test
    void returnsNullsWhenNoPreferencesExist() {
        assertThat(service.listDefaults(user.getId(), "accounts")).isEqualTo(new ListDefaults(null, null));
    }

    @Test
    void decodesBase64JsonPerPageAndSortBy() {
        preference(user, "accounts_per_page", "5");
        preference(user, "accounts_sort_by", "\"accounts.name ASC\"");

        assertThat(service.listDefaults(user.getId(), "accounts"))
            .isEqualTo(new ListDefaults(5, "accounts.name ASC"));
    }

    @Test
    void scopesLookupsToTheControllerNameAndUser() {
        preference(user, "contacts_per_page", "5");
        preference(user, "contacts_sort_by", "\"contacts.last_name ASC\"");
        preference(otherUser, "accounts_per_page", "7");
        preference(otherUser, "accounts_sort_by", "\"accounts.name DESC\"");

        assertThat(service.listDefaults(user.getId(), "accounts")).isEqualTo(new ListDefaults(null, null));
        assertThat(service.listDefaults(user.getId(), "contacts"))
            .isEqualTo(new ListDefaults(5, "contacts.last_name ASC"));
    }

    @Test
    void acceptsNumericStringsForPerPage() {
        preference(user, "accounts_per_page", "\"10\"");
        assertThat(service.listDefaults(user.getId(), "accounts").perPage()).isEqualTo(10);
    }

    @Test
    void ignoresNonPositiveAndNonIntegralPerPageValues() {
        for (String json : new String[] {"0", "-3", "\"0\"", "\"-1\"", "2.5", "\"abc\"", "true", "null", "[20]",
            "{\"value\":20}", "99999999999"}) {
            jdbcTemplate.update("DELETE FROM preferences");
            preference(user, "accounts_per_page", json);
            assertThat(service.listDefaults(user.getId(), "accounts").perPage())
                .as("per_page %s", json)
                .isNull();
        }
    }

    @Test
    void ignoresNonTextualSortByValues() {
        for (String json : new String[] {"7", "null", "true", "[\"accounts.name ASC\"]", "{\"a\":1}"}) {
            jdbcTemplate.update("DELETE FROM preferences");
            preference(user, "accounts_sort_by", json);
            assertThat(service.listDefaults(user.getId(), "accounts").sortBy())
                .as("sort_by %s", json)
                .isNull();
        }
    }

    @Test
    void ignoresNullMalformedBase64AndMalformedJsonValues() {
        rawPreference(user, "accounts_per_page", null);
        assertThat(service.listDefaults(user.getId(), "accounts").perPage()).isNull();

        jdbcTemplate.update("DELETE FROM preferences");
        rawPreference(user, "accounts_per_page", "%%%not-base64%%%");
        rawPreference(user, "accounts_sort_by", "%%%not-base64%%%");
        assertThat(service.listDefaults(user.getId(), "accounts")).isEqualTo(new ListDefaults(null, null));

        jdbcTemplate.update("DELETE FROM preferences");
        rawPreference(user, "accounts_per_page", RailsBase64.encode64("{not json"));
        rawPreference(user, "accounts_sort_by", RailsBase64.encode64(""));
        assertThat(service.listDefaults(user.getId(), "accounts")).isEqualTo(new ListDefaults(null, null));
    }

    @Test
    void readsTheLowestIdWhenDuplicatePreferenceRowsExist() {
        preference(user, "accounts_per_page", "5");
        preference(user, "accounts_per_page", "15");

        assertThat(service.listDefaults(user.getId(), "accounts").perPage()).isEqualTo(5);
    }

    @Test
    void decodesMultiLineRailsBase64() {
        String json = "\"" + "accounts.name ASC".repeat(6) + "\"";
        String encoded = java.util.Base64.getMimeEncoder(60, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(json.getBytes(StandardCharsets.UTF_8)) + "\n";
        assertThat(encoded).contains("\n");
        rawPreference(user, "accounts_sort_by", encoded);

        assertThat(service.listDefaults(user.getId(), "accounts").sortBy())
            .isEqualTo("accounts.name ASC".repeat(6));
    }

    private User user(String username) {
        User created = new User();
        created.setUsername(username);
        created.setEncryptedPassword("encrypted");
        created.setPasswordSalt("salt");
        return userRepository.saveAndFlush(created);
    }

    private void preference(User owner, String name, String json) {
        rawPreference(owner, name, RailsBase64.encode64(json));
    }

    private void rawPreference(User owner, String name, String value) {
        Preference preference = new Preference();
        preference.setUser(owner);
        preference.setName(name);
        preference.setValue(value);
        preferenceRepository.saveAndFlush(preference);
    }
}
