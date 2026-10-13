package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.service.mail.MailSettingsService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Edge and data-integrity behaviour of {@link RailsJobEnqueuer}: {@code perform_later} envelopes
 * for the enrichment jobs, the {@code MANDATORY} propagation that keeps Solid Queue rows inside the
 * caller's transaction (Rails {@code after_save_commit} never leaves a job for a rolled-back save),
 * and the refusal to serialise a GlobalID for an unsaved record.
 */
class RailsJobEnqueuerEdgeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-05-06T07:08:09.123456Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MailSettingsService mailSettings;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RailsJobEnqueuer managedEnqueuer;

    @BeforeEach
    @AfterEach
    void clearQueue() {
        jdbcTemplate.update("DELETE FROM solid_queue_ready_executions");
        jdbcTemplate.update("DELETE FROM solid_queue_jobs");
    }

    @Test
    void performLaterWritesRailsActiveJobEnvelopeForEnrichmentJobs() throws Exception {
        RailsJobEnqueuer enqueuer = new RailsJobEnqueuer(
            jdbcTemplate, objectMapper, mailSettings, Clock.fixed(NOW, ZoneOffset.UTC));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            enqueuer.performLater("AccountWebsiteJob", accountWithId(101L));
            enqueuer.performLater("WikidataJob", accountWithId(102L));
        });

        assertEquals(2, countJobs());
        assertEquals(2, countReadyExecutions());
        JsonNode website = jobArguments("AccountWebsiteJob");
        assertEquals("AccountWebsiteJob", website.path("job_class").asText());
        assertEquals("default", website.path("queue_name").asText());
        assertTrue(website.path("priority").isNull());
        assertTrue(website.path("provider_job_id").isNull());
        assertEquals(1, website.path("arguments").size());
        assertEquals("gid://fat-free-crm/Account/101",
            website.path("arguments").get(0).path("_aj_globalid").asText());
        assertEquals(0, website.path("executions").asInt());
        assertTrue(website.path("exception_executions").isObject());
        assertEquals(0, website.path("exception_executions").size());
        assertEquals(mailSettings.locale(), website.path("locale").asText());
        assertEquals("UTC", website.path("timezone").asText());
        assertEquals("2026-05-06T07:08:09.123456Z", website.path("enqueued_at").asText());
        assertEquals("2026-05-06T07:08:09.123456Z", website.path("scheduled_at").asText());
        assertEquals(website.path("job_id").asText(), jdbcTemplate.queryForObject(
            "SELECT active_job_id FROM solid_queue_jobs WHERE class_name = 'AccountWebsiteJob'", String.class));
        assertEquals("default", jdbcTemplate.queryForObject(
            "SELECT queue_name FROM solid_queue_jobs WHERE class_name = 'AccountWebsiteJob'", String.class));

        JsonNode wikidata = jobArguments("WikidataJob");
        assertEquals("gid://fat-free-crm/Account/102",
            wikidata.path("arguments").get(0).path("_aj_globalid").asText());
        assertEquals(2, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM solid_queue_ready_executions r JOIN solid_queue_jobs j ON j.id = r.job_id"
                + " WHERE r.queue_name = j.queue_name", Integer.class));
    }

    @Test
    void enqueuingOutsideATransactionIsRejected() {
        assertThrows(IllegalTransactionStateException.class,
            () -> managedEnqueuer.performLater("AccountWebsiteJob", accountWithId(101L)));
        assertThrows(IllegalTransactionStateException.class,
            () -> managedEnqueuer.deliverLater("UserMailer", "assigned_entity_notification", accountWithId(101L)));
        assertEquals(0, countJobs());
    }

    @Test
    void rolledBackTransactionLeavesNoQueueRows() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            managedEnqueuer.performLater("AccountWebsiteJob", accountWithId(101L));
            managedEnqueuer.deliverLater("UserMailer", "assigned_entity_notification", accountWithId(101L));
            assertEquals(2, countJobs());
            status.setRollbackOnly();
        });

        assertEquals(0, countJobs());
        assertEquals(0, countReadyExecutions());
    }

    @Test
    void unsavedRecordCannotBeSerialisedAsGlobalId() {
        RailsJobEnqueuer enqueuer = new RailsJobEnqueuer(
            jdbcTemplate, objectMapper, mailSettings, Clock.fixed(NOW, ZoneOffset.UTC));
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> template.executeWithoutResult(status -> enqueuer.performLater("AccountWebsiteJob", new Account())));

        assertEquals("Cannot enqueue an unsaved Account", error.getMessage());
        assertEquals(0, countJobs());
    }

    private JsonNode jobArguments(String className) throws Exception {
        return objectMapper.readTree(jdbcTemplate.queryForObject(
            "SELECT arguments FROM solid_queue_jobs WHERE class_name = ?", String.class, className));
    }

    private int countJobs() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM solid_queue_jobs", Integer.class);
    }

    private int countReadyExecutions() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM solid_queue_ready_executions", Integer.class);
    }

    private static Account accountWithId(long id) {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
