package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.JwtTokenService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class TagListUpdatedAtIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Pattern UPDATED_AT_KEY = Pattern.compile("(?m)^updated_at:\\s*$");
    private static final Pattern ROOT_KEY = Pattern.compile("(?m)^[A-Za-z_][A-Za-z0-9_]*:\\s*$");
    private static final Pattern UTC_VALUE =
        Pattern.compile("(?m)^  utc: (?:&\\d+\\s+)?(.+?)\\s*$");
    private static final Pattern YAML_TIME =
        Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(?:\\s+Z)?$");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private OpportunityRepository opportunityRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User alice;
    private String aliceBearer;
    private Account account;
    private Campaign campaign;
    private Contact contact;
    private Lead lead;
    private Opportunity opportunity;

    @BeforeEach
    void seed() {
        clearData();
        alice = user();
        aliceBearer = "Bearer " + jwtTokenService.issue(alice).accessToken();
        account = account("Tagged Account");
        campaign = campaign();
        contact = contact();
        lead = lead();
        opportunity = opportunity();
    }

    @AfterEach
    void cleanUp() {
        clearData();
    }

    @Test
    void accountTagOnlyUpdateSharesUpdatedAtWithVersion() throws Exception {
        assertTagUpdate("accounts", "Account", "accounts", "account", account.getId(),
            "\"tag_list\":\"alpha, beta\"");
    }

    @Test
    void accountTagAndNameUpdateSharesUpdatedAtWithVersion() throws Exception {
        assertTagUpdate("accounts", "Account", "accounts", "account", account.getId(),
            "\"tag_list\":\"alpha, beta\",\"name\":\"Tagged Account Updated\"");
    }

    @Test
    void campaignTagOnlyUpdateSharesUpdatedAtWithVersion() throws Exception {
        assertTagUpdate("campaigns", "Campaign", "campaigns", "campaign", campaign.getId(),
            "\"tag_list\":\"alpha, beta\"");
    }

    @Test
    void contactTagOnlyUpdateSharesUpdatedAtWithVersion() throws Exception {
        assertTagUpdate("contacts", "Contact", "contacts", "contact", contact.getId(),
            "\"tag_list\":\"alpha, beta\"");
    }

    @Test
    void leadTagOnlyUpdateSharesUpdatedAtWithVersion() throws Exception {
        assertTagUpdate("leads", "Lead", "leads", "lead", lead.getId(),
            "\"tag_list\":\"alpha, beta\"");
    }

    @Test
    void opportunityTagOnlyUpdateSharesUpdatedAtWithVersion() throws Exception {
        assertTagUpdate("opportunities", "Opportunity", "opportunities", "opportunity",
            opportunity.getId(), "\"tag_list\":\"alpha, beta\"");
    }

    private void assertTagUpdate(
        String table,
        String model,
        String resource,
        String requestKey,
        Long id,
        String attributes
    ) throws Exception {
        Instant before = rowUpdatedAt(table, id);
        mockMvc.perform(put("/api/v1/" + resource + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"" + requestKey + "\":{" + attributes + "}}"))
            .andExpect(status().isNoContent());

        Instant rowUpdatedAt = rowUpdatedAt(table, id);
        assertThat(rowUpdatedAt).isNotEqualTo(before);

        String objectChanges = jdbcTemplate.queryForObject(
            "SELECT object_changes FROM versions "
                + "WHERE item_type = ? AND item_id = ? AND event = 'update' "
                + "ORDER BY id DESC LIMIT 1",
            String.class,
            model,
            id.intValue()
        );
        List<Instant> versionUpdatedAts = updatedAtValues(objectChanges);
        assertThat(versionUpdatedAts).hasSize(2);
        assertThat(versionUpdatedAts.get(1)).isEqualTo(rowUpdatedAt);
    }

    private Instant rowUpdatedAt(String table, Long id) {
        String value = jdbcTemplate.queryForObject(
            "SELECT to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS.US') FROM " + table + " WHERE id = ?",
            String.class,
            id.intValue()
        );
        return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }

    private static List<Instant> updatedAtValues(String objectChanges) {
        Matcher updatedAt = UPDATED_AT_KEY.matcher(objectChanges);
        assertThat(updatedAt.find()).isTrue();
        int start = updatedAt.end();
        int end = objectChanges.length();

        Matcher nextRootKey = ROOT_KEY.matcher(objectChanges);
        while (nextRootKey.find()) {
            if (nextRootKey.start() > start) {
                end = nextRootKey.start();
                break;
            }
        }

        List<Instant> values = new ArrayList<>();
        Matcher utcValue = UTC_VALUE.matcher(objectChanges.substring(start, end));
        while (utcValue.find()) {
            Matcher time = YAML_TIME.matcher(utcValue.group(1));
            assertThat(time.matches()).isTrue();
            String fractionalSeconds = time.group(3) == null
                ? "000000"
                : (time.group(3) + "000000").substring(0, 6);
            values.add(LocalDateTime.parse(
                time.group(1) + "T" + time.group(2) + "." + fractionalSeconds)
                .toInstant(ZoneOffset.UTC));
        }
        return values;
    }

    private User user() {
        User user = new User();
        user.setUsername("tag-list-alice");
        user.setEncryptedPassword("encrypted");
        user.setPasswordSalt("salt");
        user.setAdmin(false);
        user.setConfirmedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return userRepository.saveAndFlush(user);
    }

    private Account account(String name) {
        Account entity = new Account();
        entity.setName(name);
        entity.setEmail("account@example.test");
        entity.setAccess("Public");
        entity.setUser(alice);
        return accountRepository.saveAndFlush(entity);
    }

    private Campaign campaign() {
        Campaign entity = new Campaign();
        entity.setName("Tagged Campaign");
        entity.setStatus("planned");
        entity.setAccess("Public");
        entity.setUser(alice);
        entity.setBudget(new BigDecimal("100.50"));
        entity.setTargetLeads(10);
        entity.setTargetRevenue(new BigDecimal("200.75"));
        entity.setRevenue(new BigDecimal("150.25"));
        entity.setStartsOn(LocalDate.parse("2025-03-01"));
        entity.setEndsOn(LocalDate.parse("2025-04-01"));
        return campaignRepository.saveAndFlush(entity);
    }

    private Contact contact() {
        Contact entity = new Contact();
        entity.setFirstName("Tag");
        entity.setLastName("Contact");
        entity.setEmail("tag-contact@example.test");
        entity.setAccess("Public");
        entity.setUser(alice);
        return contactRepository.saveAndFlush(entity);
    }

    private Lead lead() {
        Lead entity = new Lead();
        entity.setFirstName("Tag");
        entity.setLastName("Lead");
        entity.setCompany("Example");
        entity.setEmail("tag-lead@example.test");
        entity.setStatus("new");
        entity.setAccess("Public");
        entity.setUser(alice);
        return leadRepository.saveAndFlush(entity);
    }

    private Opportunity opportunity() {
        Opportunity entity = new Opportunity();
        entity.setName("Tagged Opportunity");
        entity.setStage("prospecting");
        entity.setAccess("Public");
        entity.setUser(alice);
        entity.setAmount(new BigDecimal("100.00"));
        entity.setProbability(50);
        return opportunityRepository.saveAndFlush(entity);
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM versions");
        jdbcTemplate.update("DELETE FROM taggings");
        jdbcTemplate.update("DELETE FROM tags");
        jdbcTemplate.update("DELETE FROM preferences");
        jdbcTemplate.update("DELETE FROM account_contacts");
        jdbcTemplate.update("DELETE FROM account_opportunities");
        jdbcTemplate.update("DELETE FROM contact_opportunities");
        jdbcTemplate.update("DELETE FROM opportunities");
        jdbcTemplate.update("DELETE FROM contacts");
        jdbcTemplate.update("DELETE FROM leads");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM campaigns");
        jdbcTemplate.update("DELETE FROM users");
    }
}
