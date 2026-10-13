package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Edge behaviour of the Solid Queue writer through the Spring proxy: the queue row must share the
 * caller's transaction (Rails enqueues from {@code after_save_commit} / observer callbacks inside
 * the request transaction), unsaved records have no Global ID, and {@code perform_later} jobs use
 * the plain ActiveJob envelope rather than the Action Mailer one.
 */
class RailsJobEnqueuerEdgeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RailsJobEnqueuer enqueuer;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void deliverLaterRequiresAnExistingTransaction() {
        int jobsBefore = jobCount();
        assertThrows(IllegalTransactionStateException.class,
            () -> enqueuer.deliverLater("UserMailer", "assigned_entity_notification", accountWithId(), userWithId()));
        assertEquals(jobsBefore, jobCount());
    }

    @Test
    void performLaterRequiresAnExistingTransaction() {
        int jobsBefore = jobCount();
        assertThrows(IllegalTransactionStateException.class,
            () -> enqueuer.performLater("AccountWebsiteJob", accountWithId()));
        assertEquals(jobsBefore, jobCount());
    }

    @Test
    void unsavedRecordsHaveNoGlobalIdAndEnqueueNothing() {
        int jobsBefore = jobCount();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(status ->
            enqueuer.deliverLater("UserMailer", "assigned_entity_notification", new Account(), userWithId())));
        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(status ->
            enqueuer.performLater("AccountWebsiteJob", new Account())));
        assertEquals(jobsBefore, jobCount());
    }

    @Test
    void rolledBackTransactionLeavesNoQueueOrReadyExecutionRows() {
        int jobsBefore = jobCount();
        int readyBefore = readyExecutionCount();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            enqueuer.deliverLater("UserMailer", "assigned_entity_notification", accountWithId(), userWithId());
            enqueuer.performLater("AccountWebsiteJob", accountWithId());
            assertEquals(jobsBefore + 2, jobCount());
            status.setRollbackOnly();
        });
        assertEquals(jobsBefore, jobCount());
        assertEquals(readyBefore, readyExecutionCount());
    }

    @Test
    void performLaterWritesPlainActiveJobEnvelope() throws Exception {
        List<String> activeJobIds = new ArrayList<>();
        try {
            String jobId = new TransactionTemplate(transactionManager).execute(status -> {
                enqueuer.performLater("WikidataJob", accountWithId());
                return jdbcTemplate.queryForObject(
                    "SELECT active_job_id FROM solid_queue_jobs ORDER BY id DESC LIMIT 1", String.class);
            });
            activeJobIds.add(jobId);

            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT class_name, queue_name, priority, arguments::text AS arguments, concurrency_key"
                    + " FROM solid_queue_jobs WHERE active_job_id = ?", jobId);
            assertEquals("WikidataJob", row.get("class_name"));
            assertEquals("default", row.get("queue_name"));
            assertEquals(0, ((Number) row.get("priority")).intValue());
            assertEquals(null, row.get("concurrency_key"));

            ObjectNode payload = (ObjectNode) objectMapper.readTree((String) row.get("arguments"));
            assertEquals(jobId, payload.path("job_id").asText());
            assertEquals(payload.path("enqueued_at"), payload.path("scheduled_at"));
            payload.remove(List.of("job_id", "enqueued_at", "scheduled_at"));
            JsonNode expected = objectMapper.readTree("""
                {
                  "job_class":"WikidataJob",
                  "provider_job_id":null,
                  "queue_name":"default",
                  "priority":null,
                  "arguments":[{"_aj_globalid":"gid://fat-free-crm/Account/101"}],
                  "executions":0,
                  "exception_executions":{},
                  "locale":"en-US",
                  "timezone":"UTC"
                }
                """);
            assertEquals(expected, payload);
            assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM solid_queue_ready_executions
                WHERE job_id = (SELECT id FROM solid_queue_jobs WHERE active_job_id = ?)
                AND queue_name = 'default' AND priority = 0
                """, Integer.class, jobId));
        } finally {
            cleanup(activeJobIds);
        }
    }

    private int jobCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM solid_queue_jobs", Integer.class);
    }

    private int readyExecutionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM solid_queue_ready_executions", Integer.class);
    }

    private static Account accountWithId() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", 101L);
        return account;
    }

    private static User userWithId() {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", 2L);
        return user;
    }

    private void cleanup(List<String> activeJobIds) {
        for (String activeJobId : activeJobIds) {
            jdbcTemplate.update("""
                DELETE FROM solid_queue_ready_executions
                WHERE job_id = (SELECT id FROM solid_queue_jobs WHERE active_job_id = ?)
                """, activeJobId);
            jdbcTemplate.update("DELETE FROM solid_queue_jobs WHERE active_job_id = ?", activeJobId);
        }
    }
}
