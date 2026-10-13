package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.service.mail.MailSettingsService;
import com.fatfreecrm.support.AbstractPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class RailsJobEnqueuerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-05-06T07:08:09.123456Z");

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MailSettingsService mailSettings;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void writesRailsActionMailerEnvelopeAndReadyExecution() throws Exception {
        List<String> activeJobIds = new ArrayList<>();
        try {
            RailsJobEnqueuer enqueuer = new RailsJobEnqueuer(
                jdbcTemplate, objectMapper, mailSettings, Clock.fixed(NOW, ZoneOffset.UTC));
            String jobId = new TransactionTemplate(transactionManager).execute(status -> {
                Account account = accountWithId();
                User user = userWithId();
                enqueuer.deliverLater("UserMailer", "assigned_entity_notification", account, user);
                return jdbcTemplate.queryForObject(
                    "SELECT active_job_id FROM solid_queue_jobs ORDER BY id DESC LIMIT 1", String.class);
            });
            activeJobIds.add(jobId);

            String arguments = jdbcTemplate.queryForObject(
                "SELECT arguments::text FROM solid_queue_jobs WHERE active_job_id = ?",
                String.class, jobId);
            JsonNode payload = objectMapper.readTree(arguments);
            JsonNode expected = objectMapper.readTree("""
                {
                  "job_class":"ActionMailer::MailDeliveryJob",
                  "job_id":"%s",
                  "provider_job_id":null,
                  "queue_name":"default",
                  "priority":null,
                  "arguments":[
                    "UserMailer",
                    "assigned_entity_notification",
                    "deliver_now",
                    {
                      "_aj_ruby2_keywords":["args"],
                      "args":[
                        {"_aj_globalid":"gid://fat-free-crm/Account/101"},
                        {"_aj_globalid":"gid://fat-free-crm/User/2"}
                      ]
                    }
                  ],
                  "executions":0,
                  "exception_executions":{},
                  "locale":"en-US",
                  "timezone":"UTC",
                  "enqueued_at":"2026-05-06T07:08:09.123456Z",
                  "scheduled_at":"2026-05-06T07:08:09.123456Z"
                }
                """.formatted(jobId));
            assertEquals(expected, payload);
            assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM solid_queue_ready_executions
                WHERE job_id = (SELECT id FROM solid_queue_jobs WHERE active_job_id = ?)
                AND queue_name = 'default' AND priority = 0
                """, Integer.class, jobId));
            assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT count(*) FROM solid_queue_jobs
                WHERE active_job_id = ? AND concurrency_key IS NOT NULL
                """, Integer.class, jobId));
        } finally {
            cleanup(activeJobIds);
        }
    }

    @Test
    void springOwnerDrainsEnqueuedAssignmentMailAndWebsiteJobs() throws Exception {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");
        SolidQueueJobExecutor executor = mock(SolidQueueJobExecutor.class);
        SolidQueueDrainService drainService = new SolidQueueDrainService(
            jdbcTemplate, objectMapper, executor, new JobsOwner(properties), properties, transactionManager);
        List<String> activeJobIds = new ArrayList<>();
        try {
            RailsJobEnqueuer enqueuer = new RailsJobEnqueuer(
                jdbcTemplate, objectMapper, mailSettings, Clock.fixed(NOW, ZoneOffset.UTC));
            activeJobIds.addAll(new TransactionTemplate(transactionManager).execute(status -> {
                Account account = accountWithId();
                User user = userWithId();
                enqueuer.deliverLater("UserMailer", "assigned_entity_notification", account, user);
                enqueuer.performLater("AccountWebsiteJob", account);
                return jdbcTemplate.query(
                    "SELECT active_job_id FROM solid_queue_jobs ORDER BY id DESC LIMIT 2",
                    (row, index) -> row.getString(1));
            }));

            assertEquals(2, drainService.drain());
            verify(executor).execute("ActionMailer::MailDeliveryJob", queuedArguments(activeJobIds.get(1)));
            verify(executor).execute("AccountWebsiteJob", queuedArguments(activeJobIds.get(0)));
        } finally {
            cleanup(activeJobIds);
        }
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

    private String queuedArguments(String activeJobId) {
        return jdbcTemplate.queryForObject(
            "SELECT arguments::text FROM solid_queue_jobs WHERE active_job_id = ?",
            String.class, activeJobId);
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
