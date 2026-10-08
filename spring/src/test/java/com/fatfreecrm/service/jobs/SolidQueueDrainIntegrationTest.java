package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
class SolidQueueDrainIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void drainsRailsShapedRowsAndPersistsSuccessFailureAndUnsupportedStates() throws Exception {
        String prefix = "ab273-" + UUID.randomUUID();
        try {
            JsonNode payloads = objectMapper.readTree(
                getClass().getResourceAsStream("/mail/active_job_arguments.json"));
            String successArguments = objectMapper.writeValueAsString(
                payloads.path("account_website").path("solid_queue"));
            String failureArguments = objectMapper.writeValueAsString(
                payloads.path("wikidata").path("solid_queue"));
            long successfulJob = enqueue(prefix + "-success", "AccountWebsiteJob", successArguments);
            long failedJob = enqueue(prefix + "-failure", "WikidataJob", failureArguments);
            long unsupportedJob = enqueue(prefix + "-unsupported", "UnsupportedJob", "{}");

            JobsProperties properties = new JobsProperties();
            properties.setOwner("spring");
            SolidQueueJobExecutor executor = mock(SolidQueueJobExecutor.class);
            doThrow(new IllegalStateException("expected failure"))
                .when(executor).execute("WikidataJob", failureArguments);
            SolidQueueDrainService service = new SolidQueueDrainService(
                jdbcTemplate, objectMapper, executor, new JobsOwner(properties), transactionManager);

            assertEquals(2, service.drain());
            verify(executor).execute("AccountWebsiteJob", successArguments);
            verify(executor).execute("WikidataJob", failureArguments);
            assertNotNull(finishedAt(successfulJob));
            assertNotNull(finishedAt(failedJob));
            assertNull(finishedAt(unsupportedJob));
            assertEquals(1, count("solid_queue_failed_executions", failedJob));
            assertEquals(0, count("solid_queue_failed_executions", successfulJob));
            assertEquals(0, count("solid_queue_claimed_executions", successfulJob));
            assertEquals(0, count("solid_queue_claimed_executions", failedJob));
            assertEquals(1, count("solid_queue_ready_executions", unsupportedJob));
        } finally {
            jdbcTemplate.update("DELETE FROM solid_queue_failed_executions WHERE job_id IN "
                + "(SELECT id FROM solid_queue_jobs WHERE active_job_id LIKE ?)", prefix + "%");
            jdbcTemplate.update("DELETE FROM solid_queue_ready_executions WHERE job_id IN "
                + "(SELECT id FROM solid_queue_jobs WHERE active_job_id LIKE ?)", prefix + "%");
            jdbcTemplate.update("DELETE FROM solid_queue_claimed_executions WHERE job_id IN "
                + "(SELECT id FROM solid_queue_jobs WHERE active_job_id LIKE ?)", prefix + "%");
            jdbcTemplate.update("DELETE FROM solid_queue_jobs WHERE active_job_id LIKE ?", prefix + "%");
        }
    }

    private long enqueue(String activeJobId, String className, String arguments) {
        long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO solid_queue_jobs
                (queue_name, class_name, arguments, active_job_id, created_at, updated_at)
            VALUES ('default', ?, ?, ?, ?, ?)
            RETURNING id
            """, Long.class, className, arguments, activeJobId, Timestamp.from(Instant.now()),
            Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
            INSERT INTO solid_queue_ready_executions (job_id, queue_name, priority, created_at)
            VALUES (?, 'default', 0, ?)
            """, jobId, Timestamp.from(Instant.now()));
        return jobId;
    }

    private Instant finishedAt(long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT finished_at FROM solid_queue_jobs WHERE id = ?",
            (row, index) -> row.getTimestamp(1) == null ? null : row.getTimestamp(1).toInstant(),
            jobId);
    }

    private int count(String table, long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE job_id = ?", Integer.class, jobId);
    }
}
