package com.fatfreecrm.service.jobs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed JdbcTemplate is intentionally retained by this service."
)
public class SolidQueueDrainService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolidQueueDrainService.class);
    private static final int BATCH_SIZE = 25;
    private static final String SUPPORTED_CLASS_FILTER = """
        class_name IN ('ActionMailer::MailDeliveryJob', 'AccountWebsiteJob', 'WikidataJob')
        """;

    private final JdbcTemplate jdbcTemplate;
    private final SolidQueueJobExecutor executor;
    private final JobsOwner jobsOwner;
    private final TransactionTemplate transactionTemplate;

    public SolidQueueDrainService(
        JdbcTemplate jdbcTemplate,
        SolidQueueJobExecutor executor,
        JobsOwner jobsOwner,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.executor = executor;
        this.jobsOwner = jobsOwner;
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int drain() {
        if (!jobsOwner.isSpring()) {
            return 0;
        }
        List<Long> candidates = jdbcTemplate.query(
            """
                SELECT jobs.id
                FROM solid_queue_ready_executions ready
                JOIN solid_queue_jobs jobs ON jobs.id = ready.job_id
                WHERE jobs.finished_at IS NULL AND %s
                ORDER BY ready.priority, ready.job_id
                LIMIT ?
                """.replace("%s", SUPPORTED_CLASS_FILTER),
            (result, row) -> result.getLong(1),
            BATCH_SIZE);
        int count = 0;
        for (Long id : candidates) {
            SolidQueueJob job = transactionTemplate.execute(status -> claim(id));
            if (job == null) {
                continue;
            }
            try {
                executor.execute(job.className(), job.arguments());
                complete(job.id());
            } catch (Exception exception) {
                fail(job.id(), exception);
            }
            count++;
        }
        return count;
    }

    private SolidQueueJob claim(long id) {
        List<SolidQueueJob> jobs = jdbcTemplate.query(
            """
                SELECT jobs.id, jobs.class_name, jobs.arguments
                FROM solid_queue_ready_executions ready
                JOIN solid_queue_jobs jobs ON jobs.id = ready.job_id
                WHERE jobs.id = ? AND jobs.finished_at IS NULL
                  AND %s
                FOR UPDATE OF ready SKIP LOCKED
                """.replace("%s", SUPPORTED_CLASS_FILTER),
            new SolidQueueJobMapper(),
            id);
        if (jobs.isEmpty()) {
            return null;
        }
        SolidQueueJob job = jobs.get(0);
        int deleted = jdbcTemplate.update("DELETE FROM solid_queue_ready_executions WHERE job_id = ?", id);
        if (deleted == 0) {
            return null;
        }
        jdbcTemplate.update(
            "INSERT INTO solid_queue_claimed_executions(job_id, process_id, created_at) VALUES (?, NULL, ?)",
            id,
            Instant.now());
        return job;
    }

    private void complete(long id) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM solid_queue_claimed_executions WHERE job_id = ?", id);
            jdbcTemplate.update("UPDATE solid_queue_jobs SET finished_at = ?, updated_at = ? WHERE id = ?",
                Instant.now(), Instant.now(), id);
        });
    }

    private void fail(long id, Exception exception) {
        LOGGER.error("Solid Queue job {} failed", id, exception);
        String error = exception.getClass().getName() + ": " + exception.getMessage();
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM solid_queue_claimed_executions WHERE job_id = ?", id);
            jdbcTemplate.update(
                "INSERT INTO solid_queue_failed_executions(job_id, error, created_at) VALUES (?, ?, ?)",
                id,
                error,
                Instant.now());
            jdbcTemplate.update("UPDATE solid_queue_jobs SET finished_at = ?, updated_at = ? WHERE id = ?",
                Instant.now(), Instant.now(), id);
        });
    }

    private record SolidQueueJob(long id, String className, String arguments) {
    }

    private static class SolidQueueJobMapper implements RowMapper<SolidQueueJob> {

        @Override
        public SolidQueueJob mapRow(ResultSet result, int rowNumber) throws SQLException {
            return new SolidQueueJob(result.getLong("id"), result.getString("class_name"),
                result.getString("arguments"));
        }
    }
}
