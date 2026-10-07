package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Service-level coverage of the Ransack parser, text search, tag search, sorting and pagination. */
@Transactional
class CrmQueryServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private AccountContactRepository accountContactRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private TaggingRepository taggingRepository;

    @Autowired
    private CrmQueryService queryService;

    @Autowired
    private SettingRepository settingRepository;

    private AuthenticatedUser alice;

    private static User user(String username, boolean admin) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.example");
        user.setEncryptedPassword("enc");
        user.setPasswordSalt("salt");
        user.setAdmin(admin);
        return user;
    }

    private Account account(
        String name,
        String email,
        String category,
        int rating,
        String access,
        User user,
        Instant createdAt
    ) {
        Account account = new Account();
        account.setName(name);
        account.setEmail(email);
        account.setCategory(category);
        account.setRating(rating);
        account.setAccess(access);
        account.setUser(user);
        account.setCreatedAt(createdAt);
        account.setUpdatedAt(createdAt);
        return accountRepository.save(account);
    }

    private void tag(Account account, Tag tag) {
        Tagging tagging = new Tagging();
        tagging.setTag(tag);
        tagging.setTaggableType("Account");
        tagging.setTaggableId(account.getId().intValue());
        tagging.setContext("tags");
        taggingRepository.save(tagging);
    }

    private ListQuery query(String... entries) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (String entry : entries) {
            for (String pair : entry.split("&")) {
                int equals = pair.indexOf('=');
                params.add(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return ListQuery.fromParameters(params);
    }

    private List<Long> ids(String... params) {
        return queryService.list(alice, Account.class, query(params)).items().stream()
            .map(Account::getId)
            .toList();
    }

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM account_contacts");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");

        User aliceUser = userRepository.save(user("alice", false));
        User bob = userRepository.save(user("bob", false));
        alice = new AuthenticatedUser(aliceUser.getId(), "alice", false);

        account("Acme Corp", "acme@example.com", "customer", 5, "Public", bob,
            Instant.parse("2025-01-01T09:00:00Z"));
        Account beta = account("Beta Industries", "sales@beta.test", "partner", 3, "Private", aliceUser,
            Instant.parse("2025-01-02T09:00:00Z"));
        account("Gamma LLC", "info@gamma.test", "vendor", 1, "Private", bob,
            Instant.parse("2025-01-03T09:00:00Z"));
        Account delta = account("Delta Co", "contact@delta.test", null, 4, "Private", bob,
            Instant.parse("2025-01-04T09:00:00Z"));
        delta.setAssignedTo(aliceUser);
        accountRepository.save(delta);
        account("Epsilon 100% Ltd", "hello@epsilon.test", "customer", 0, "Public", bob,
            Instant.parse("2025-01-05T09:00:00Z"));
        account("acme_widgets", "widgets@acme.test", "competitor", 2, "Public", aliceUser,
            Instant.parse("2025-01-06T09:00:00Z"));

        Tag vip = new Tag();
        vip.setName("vip");
        tagRepository.save(vip);
        Tag west = new Tag();
        west.setName("west");
        tagRepository.save(west);
        tag(beta, vip);
        tag(delta, vip);
        tag(delta, west);

        Contact john = new Contact();
        john.setFirstName("John");
        john.setLastName("Smith");
        john.setUser(aliceUser);
        john.setAccess("Public");
        contactRepository.save(john);
        Contact johnDoe = new Contact();
        johnDoe.setFirstName("John");
        johnDoe.setLastName("Doe");
        johnDoe.setUser(aliceUser);
        johnDoe.setAccess("Public");
        contactRepository.save(johnDoe);
        for (Account target : List.of(
            accountRepository.findAll().stream().filter(a -> a.getName().equals("Acme Corp")).findFirst().orElseThrow(),
            delta)) {
            AccountContact link = new AccountContact();
            link.setAccount(target);
            link.setContact(john);
            accountContactRepository.save(link);
        }
        AccountContact link = new AccountContact();
        link.setAccount(accountRepository.findAll().stream()
            .filter(a -> a.getName().equals("acme_widgets")).findFirst().orElseThrow());
        link.setContact(johnDoe);
        accountContactRepository.save(link);
    }

    @Test
    void accessibleOnlyAppliesByDefault() {
        // 5 accounts visible to alice: Public x3 (Acme, Epsilon, acme_widgets), own Private (Beta), assigned (Delta).
        assertThat(ids("per_page=200")).containsExactlyInAnyOrder(
            idOf("Acme Corp"), idOf("Beta Industries"), idOf("Delta Co"), idOf("Epsilon 100% Ltd"),
            idOf("acme_widgets"));
    }

    private Long idOf(String name) {
        return accountRepository.findAll().stream()
            .filter(account -> account.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    @Test
    void stringPredicates() {
        assertThat(ids("q[name_cont]=co", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("Delta Co"));
        assertThat(ids("q[name_not_cont]=co", "per_page=200")).doesNotContain(
            idOf("Acme Corp"), idOf("Delta Co"));
        assertThat(ids("q[name_start]=acme", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("acme_widgets"));
        assertThat(ids("q[name_end]=ltd", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Epsilon 100% Ltd"));
        assertThat(ids("q[name_matches]=%widgets%", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("acme_widgets"));
        assertThat(ids("q[name_does_not_match]=%widgets%", "per_page=200"))
            .doesNotContain(idOf("acme_widgets"));
    }

    @Test
    void likeEscapingPreventsWildcardInjection() {
        // '100%' must match literally, not as a wildcard.
        assertThat(ids("q[name_cont]=100%", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Epsilon 100% Ltd"));
        // '_' must not act as a single-char wildcard.
        assertThat(ids("q[name_cont]=acme_", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("acme_widgets"));
    }

    @Test
    void comparisonAndSetPredicates() {
        assertThat(ids("q[rating_eq]=5", "per_page=200")).containsExactlyInAnyOrder(idOf("Acme Corp"));
        assertThat(ids("q[rating_not_eq]=5", "per_page=200")).doesNotContain(idOf("Acme Corp"));
        assertThat(ids("q[rating_gteq]=4", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("Delta Co"));
        assertThat(ids("q[rating_lteq]=0", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Epsilon 100% Ltd"));
        assertThat(ids("q[rating_in][]=2&q[rating_in][]=3", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Beta Industries"), idOf("acme_widgets"));
        assertThat(ids("q[rating_not_in][]=5", "per_page=200")).doesNotContain(idOf("Acme Corp"));
        // Ruby to_i coercion: 'abc' -> 0
        assertThat(ids("q[rating_eq]=abc", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Epsilon 100% Ltd"));
    }

    @Test
    void nullBlankPresentPredicates() {
        assertThat(ids("q[category_null]=1", "per_page=200")).containsExactlyInAnyOrder(idOf("Delta Co"));
        assertThat(ids("q[category_not_null]=1", "per_page=200")).doesNotContain(idOf("Delta Co"));
        assertThat(ids("q[email_present]=1", "per_page=200")).hasSize(5);
        assertThat(ids("q[category_eq]=partner", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Beta Industries"));
    }

    @Test
    void datetimeCoercionUsesNoonUtc() {
        // created_at_gteq=2025-01-04 is noon UTC: Delta (09:00 on the 4th) is EXCLUDED.
        assertThat(ids("q[created_at_gteq]=2025-01-04", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Epsilon 100% Ltd"), idOf("acme_widgets"));
        assertThat(ids("q[created_at_lteq]=2025-01-02", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("Beta Industries"));
        // Uncastable (non-ISO) value is dropped like Rails.
        assertThat(ids("q[created_at_gteq]=yesterday", "per_page=200")).hasSize(5);
    }

    @Test
    void orAndAttributeCombinations() {
        assertThat(ids("q[name_or_email_cont]=beta", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Beta Industries"));
        assertThat(ids("q[name_and_category_cont]=cust", "per_page=200")).isEmpty();
    }

    @Test
    void compoundPredicatesAnyAndAll() {
        assertThat(ids("q[category_eq_any][]=customer&q[category_eq_any][]=partner", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("Epsilon 100% Ltd"), idOf("Beta Industries"));
        assertThat(ids("q[name_cont_all][]=a&q[name_cont_all][]=e", "per_page=200")).isNotEmpty();
    }

    @Test
    void blankValuesAreDropped() {
        // q[name_cont]= blank -> dropped entirely, all 5 visible rows.
        assertThat(ids("q[name_cont]= ", "per_page=200")).hasSize(5);
        assertThat(ids("q[rating_in][]=2&q[rating_in][]=", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("acme_widgets"));
    }

    @Test
    void combinatorOrAndGroups() {
        assertThat(ids("q[m]=or&q[category_eq]=vendor&q[rating_eq]=5", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"));
        assertThat(ids(
            "q[g][0][m]=or&q[g][0][name_cont]=delta&q[g][0][email_cont]=beta&q[access_eq]=Private",
            "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Beta Industries"), idOf("Delta Co"));
    }

    @Test
    void conditionFormC() {
        assertThat(ids(
            "q[c][0][a][0][name]=name&q[c][0][p]=cont&q[c][0][v][0][value]=acme", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("acme_widgets"));
    }

    @Test
    void associationTraversalAndFanOut() {
        // John Smith is linked to Acme Corp AND Delta Co: both match, once each.
        ListResult<Account> result = queryService.list(
            alice, Account.class, query("q[contacts_first_name_cont]=john"));
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.items()).extracting(Account::getId)
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("Delta Co"), idOf("acme_widgets"));
        // Two-hop traversal contacts.account via Contact's whitelist is not exposed on Account
        // (contacts -> account would need Contact.account, which IS whitelisted):
        assertThat(ids("q[contacts_account_name_cont]=acme", "per_page=200")).isNotEmpty();
        // Non-whitelisted hop is unknown and dropped.
        assertThat(ids("q[bogusassoc_name_cont]=x", "per_page=200")).hasSize(5);
        // Tag association traversal.
        assertThat(ids("q[tags_name_eq]=vip", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Beta Industries"), idOf("Delta Co"));
    }

    @Test
    void textSearchApplies() {
        assertThat(ids("query=acme", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Acme Corp"), idOf("acme_widgets"));
        assertThat(ids("query=example.com", "per_page=200")).containsExactlyInAnyOrder(idOf("Acme Corp"));
        // Escaped: 100% is literal.
        assertThat(ids("query=100%", "per_page=200")).containsExactlyInAnyOrder(idOf("Epsilon 100% Ltd"));
    }

    @Test
    void tagSearch() {
        assertThat(ids("query=#vip", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("Beta Industries"), idOf("Delta Co"));
        assertThat(ids("query=#vip #west", "per_page=200")).containsExactlyInAnyOrder(idOf("Delta Co"));
        assertThat(ids("query=acme #nope", "per_page=200")).isEmpty();
        // Two taggings on the same account must not duplicate the row.
        ListResult<Account> result = queryService.list(alice, Account.class, query("query=#vip"));
        assertThat(result.items()).hasSize(result.items().stream().distinct().toList().size());
    }

    @Test
    void sorting() {
        // Default: created_at DESC.
        assertThat(ids("per_page=200")).startsWith(idOf("acme_widgets"), idOf("Epsilon 100% Ltd"));
        // sort_by field key.
        assertThat(ids("sort_by=name", "per_page=200")).startsWith(idOf("Acme Corp"), idOf("acme_widgets"));
        // Rails preference form.
        assertThat(ids("sort_by=accounts.name ASC", "per_page=200")).startsWith(idOf("Acme Corp"));
        // Unknown sort falls back to default.
        assertThat(ids("sort_by=bogus", "per_page=200")).startsWith(idOf("acme_widgets"));
        // Advanced q[s] ordering.
        assertThat(ids("q[s]=name asc", "per_page=200")).startsWith(idOf("Acme Corp"), idOf("acme_widgets"));
        assertThat(ids("q[s]=name desc", "per_page=200")).startsWith(idOf("Epsilon 100% Ltd"));
    }

    @Test
    void pagination() {
        ListResult<Account> all = queryService.list(alice, Account.class, query("per_page=200"));
        assertThat(all.totalCount()).isEqualTo(5);
        assertThat(all.totalPages()).isEqualTo(1);

        ListResult<Account> page = queryService.list(alice, Account.class, query("per_page=2&page=2"));
        assertThat(page.items()).hasSize(2);
        assertThat(page.totalPages()).isEqualTo(3);

        ListResult<Account> beyond = queryService.list(alice, Account.class, query("per_page=2&page=99"));
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalCount()).isEqualTo(5);
        assertThat(beyond.page()).isEqualTo(99);

        for (String perPage : List.of("0", "-1", "abc")) {
            ListResult<Account> clamped = queryService.list(alice, Account.class, query("per_page=" + perPage));
            assertThat(clamped.perPage()).isEqualTo(1);
            assertThat(clamped.items()).hasSize(1);
        }
        assertThat(queryService.list(alice, Account.class, query("per_page=201")).perPage()).isEqualTo(200);

        for (String badPage : List.of("0", "-1", "abc")) {
            assertThatThrownBy(() -> queryService.list(alice, Account.class, query("page=" + badPage)))
                .isInstanceOf(InvalidPageException.class);
        }
    }

    @Test
    void facetKeysStripYamlSymbolsFromSettings() {
        Setting setting = new Setting();
        setting.setName("account_category");
        setting.setValue("---\n- :affiliate\n- :custom\n");
        settingRepository.save(setting);
        Map<String, Long> category = queryService.list(alice, Account.class, query("per_page=1"))
            .facets().get("category");
        assertThat(category).containsKey("affiliate").containsKey("custom");
        assertThat(category.get("affiliate")).isEqualTo(0);
        assertThat(category).doesNotContainKey(":affiliate");
    }

    @Test
    void repeatedScalarKeyKeepsLastValue() {
        assertThat(ids("q[name_cont]=a&q[name_cont]=widgets", "per_page=200"))
            .containsExactlyInAnyOrder(idOf("acme_widgets"));
    }

    @Test
    void excessiveParamDepthThrows() {
        StringBuilder tooDeep = new StringBuilder("q[a]");
        for (int index = 0; index < 32; index++) {
            tooDeep.append("[b]");
        }
        assertThatThrownBy(() -> queryService.list(alice, Account.class, query(tooDeep + "=1")))
            .isInstanceOf(InvalidSearchQueryException.class);
        assertThat(queryService.list(alice, Account.class,
            query("q[g][0][name_cont]=acme", "per_page=200")).totalCount()).isEqualTo(2);
    }

    @Test
    void opportunityNumericQueryOverflowFallsBackToNameMatch() {
        ListResult<Opportunity> result = queryService.list(alice, Opportunity.class,
            query("query=9999999999999999999999999"));
        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
    }

    @Test
    void unknownConditionsAreDroppedByDefault() {
        assertThat(ids("q[bogus_cont]=x", "per_page=200")).hasSize(5);
        assertThat(ids("q[name_bogus]=x", "per_page=200")).hasSize(5);
    }

    @Test
    void malformedConditionsThrow() {
        assertThatThrownBy(() -> queryService.list(alice, Account.class,
            query("q[name_cont][x]=1"))).isInstanceOf(InvalidSearchQueryException.class);
        assertThatThrownBy(() -> queryService.list(alice, Account.class,
            query("q[g]=scalar"))).isInstanceOf(InvalidSearchQueryException.class);
        assertThatThrownBy(() -> queryService.list(alice, Account.class,
            query("q[m]=sideways&q[name_cont]=a"))).isInstanceOf(InvalidSearchQueryException.class);
    }

    @Test
    void facetsOnAccounts() {
        ListResult<Account> result = queryService.list(alice, Account.class, query("q[name_cont]=zzzzz"));
        Map<String, Long> category = result.facets().get("category");
        assertThat(category).containsKeys("affiliate", "customer", "all", "other");
        assertThat(category.get("all")).isEqualTo(5);
        assertThat(category.get("customer")).isEqualTo(2);
    }

    @Test
    void adminSeesEveryRow() {
        ListResult<Account> asAdmin = queryService.list(
            new AuthenticatedUser(adminId(), "admin", true), Account.class, query("per_page=200"));
        assertThat(asAdmin.totalCount()).isEqualTo(6);
        assertThat(asAdmin.items()).extracting(Account::getId).contains(idOf("Gamma LLC"));
    }

    private Long adminId() {
        User admin = user("admin", true);
        return userRepository.save(admin).getId();
    }
}
