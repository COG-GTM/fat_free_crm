package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
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
            jdbcTemplate.update("""
                INSERT INTO solid_queue_failed_executions(job_id, error, created_at)
                VALUES (?, ?, ?)
                """, failedJob, "stale failure", Timestamp.from(Instant.now()));
            Instant failedUpdatedAt = updatedAt(failedJob);

            JobsProperties properties = new JobsProperties();
            properties.setOwner("spring");
            SolidQueueJobExecutor executor = mock(SolidQueueJobExecutor.class);
            doThrow(new IllegalStateException("expected failure"))
                .when(executor).execute("WikidataJob", failureArguments);
            SolidQueueDrainService service = new SolidQueueDrainService(
                jdbcTemplate, objectMapper, executor, new JobsOwner(properties), properties, transactionManager);

            assertEquals(2, service.drain());
            verify(executor).execute("AccountWebsiteJob", successArguments);
            verify(executor).execute("WikidataJob", failureArguments);
            assertNotNull(finishedAt(successfulJob));
            assertNull(finishedAt(failedJob));
            assertEquals(failedUpdatedAt, updatedAt(failedJob));
            assertNull(finishedAt(unsupportedJob));
            assertEquals(1, count("solid_queue_failed_executions", failedJob));
            assertEquals(0, count("solid_queue_failed_executions", successfulJob));
            assertEquals(0, count("solid_queue_claimed_executions", successfulJob));
            assertEquals(0, count("solid_queue_claimed_executions", failedJob));
            assertEquals(0, count("solid_queue_ready_executions", successfulJob));
            assertEquals(0, count("solid_queue_ready_executions", failedJob));
            assertEquals(1, count("solid_queue_ready_executions", unsupportedJob));
            JsonNode failure = objectMapper.readTree(failureDetails(failedJob));
            List<String> keys = new ArrayList<>();
            failure.fieldNames().forEachRemaining(keys::add);
            assertEquals(List.of("exception_class", "message", "backtrace"), keys);
            assertEquals(IllegalStateException.class.getName(), failure.path("exception_class").asText());
            assertEquals("expected failure", failure.path("message").asText());
            JsonNode backtrace = failure.path("backtrace");
            assertTrue(backtrace.isArray());
            assertTrue(backtrace.size() > 0);
            backtrace.forEach(frame -> assertTrue(frame.isTextual()));
        } finally {
            cleanup(prefix);
        }
    }

    @Test
    void nullFailureMessageUsesTheExceptionClassName() throws Exception {
        String prefix = "ab273-" + UUID.randomUUID();
        try {
            JsonNode payloads = objectMapper.readTree(
                getClass().getResourceAsStream("/mail/active_job_arguments.json"));
            String failureArguments = objectMapper.writeValueAsString(
                payloads.path("wikidata").path("solid_queue"));
            long failedJob = enqueue(prefix + "-failure", "WikidataJob", failureArguments);
            JobsProperties properties = new JobsProperties();
            properties.setOwner("spring");
            SolidQueueJobExecutor executor = mock(SolidQueueJobExecutor.class);
            doThrow(new NullMessageException()).when(executor).execute("WikidataJob", failureArguments);
            SolidQueueDrainService service = new SolidQueueDrainService(
                jdbcTemplate, objectMapper, executor, new JobsOwner(properties), properties, transactionManager);

            assertEquals(1, service.drain());

            JsonNode failure = objectMapper.readTree(failureDetails(failedJob));
            String exceptionClass = NullMessageException.class.getName();
            assertEquals(exceptionClass, failure.path("exception_class").asText());
            assertEquals(exceptionClass, failure.path("message").asText());
        } finally {
            cleanup(prefix);
        }
    }

    @Test
    void warnsAboutOnlyStaleSpringClaimsWithoutDeletingOrReleasingThem(CapturedOutput output) {
        String prefix = "ab273-" + UUID.randomUUID();
        try {
            Instant now = Instant.now();
            long staleNullProcess = enqueueWithoutReady(
                prefix + "-stale-null", Timestamp.from(now.minus(Duration.ofMinutes(20))));
            long freshNullProcess = enqueueWithoutReady(prefix + "-fresh-null", Timestamp.from(now));
            long staleWithProcess = enqueueWithoutReady(
                prefix + "-stale-process", Timestamp.from(now.minus(Duration.ofMinutes(20))));
            jdbcTemplate.update("""
                INSERT INTO solid_queue_claimed_executions(job_id, process_id, created_at)
                VALUES (?, NULL, ?), (?, NULL, ?), (?, 987654321, ?)
                """,
                staleNullProcess, Timestamp.from(now.minus(Duration.ofMinutes(20))),
                freshNullProcess, Timestamp.from(now),
                staleWithProcess, Timestamp.from(now.minus(Duration.ofMinutes(20))));

            JobsProperties properties = new JobsProperties();
            properties.setOwner("spring");
            assertEquals(Duration.ofMinutes(15), properties.getSolidQueueDrain().getStaleClaimThreshold());
            SolidQueueDrainService service = new SolidQueueDrainService(
                jdbcTemplate, objectMapper, mock(SolidQueueJobExecutor.class),
                new JobsOwner(properties), properties, transactionManager);

            assertEquals(0, service.drain());

            List<String> warningLines = output.getAll().lines()
                .filter(line -> line.contains("Solid Queue has") && line.contains("job_ids="))
                .toList();
            assertEquals(1, warningLines.size());
            String warning = warningLines.get(0);
            assertTrue(warning.contains("1 Spring claims"));
            assertTrue(warning.contains("PT15M"));
            assertTrue(warning.contains("job_ids=[" + staleNullProcess + "]"));
            assertEquals(1, count("solid_queue_claimed_executions", staleNullProcess));
            assertEquals(1, count("solid_queue_claimed_executions", freshNullProcess));
            assertEquals(1, count("solid_queue_claimed_executions", staleWithProcess));
            System.out.println("Captured Solid Queue stale-claim WARN: " + warning);
        } finally {
            cleanup(prefix);
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

    private long enqueueWithoutReady(String activeJobId, Timestamp createdAt) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO solid_queue_jobs
                (queue_name, class_name, arguments, active_job_id, created_at, updated_at)
            VALUES ('default', 'UnsupportedJob', '{}', ?, ?, ?)
            RETURNING id
            """, Long.class, activeJobId, createdAt, createdAt);
    }

    private Instant finishedAt(long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT finished_at FROM solid_queue_jobs WHERE id = ?",
            (row, index) -> row.getTimestamp(1) == null ? null : row.getTimestamp(1).toInstant(),
            jobId);
    }

    private Instant updatedAt(long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT updated_at FROM solid_queue_jobs WHERE id = ?",
            (row, index) -> row.getTimestamp(1).toInstant(),
            jobId);
    }

    private String failureDetails(long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT error FROM solid_queue_failed_executions WHERE job_id = ?",
            String.class,
            jobId);
    }

    private int count(String table, long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE job_id = ?", Integer.class, jobId);
    }

    private void cleanup(String prefix) {
        jdbcTemplate.update("DELETE FROM solid_queue_failed_executions WHERE job_id IN "
            + "(SELECT id FROM solid_queue_jobs WHERE active_job_id LIKE ?)", prefix + "%");
        jdbcTemplate.update("DELETE FROM solid_queue_ready_executions WHERE job_id IN "
            + "(SELECT id FROM solid_queue_jobs WHERE active_job_id LIKE ?)", prefix + "%");
        jdbcTemplate.update("DELETE FROM solid_queue_claimed_executions WHERE job_id IN "
            + "(SELECT id FROM solid_queue_jobs WHERE active_job_id LIKE ?)", prefix + "%");
        jdbcTemplate.update("DELETE FROM solid_queue_jobs WHERE active_job_id LIKE ?", prefix + "%");
    }

    private static final class NullMessageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            return null;
        }
    }
}
