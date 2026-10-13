package com.fatfreecrm.service.jobs;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.service.mail.MailSettingsService;
import java.lang.reflect.InvocationTargetException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class RailsJobEnqueuer {

    private static final String GLOBAL_ID_APP = "fat-free-crm";
    private static final String DEFAULT_QUEUE = "default";
    private static final DateTimeFormatter RAILS_ISO_TIME = new DateTimeFormatterBuilder()
        .appendInstant(6)
        .toFormatter();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MailSettingsService settings;
    private final Clock clock;

    public RailsJobEnqueuer(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        MailSettingsService settings,
        Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deliverLater(String mailer, String action, Object... globalIdArgs) {
        ObjectNode keywordArguments = objectMapper.createObjectNode();
        keywordArguments.putArray("_aj_ruby2_keywords").add("args");
        ArrayNode args = keywordArguments.putArray("args");
        for (Object argument : globalIdArgs) {
            args.add(globalId(argument));
        }

        ArrayNode arguments = objectMapper.createArrayNode()
            .add(mailer)
            .add(action)
            .add("deliver_now")
            .add(keywordArguments);
        enqueue("ActionMailer::MailDeliveryJob", arguments);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void performLater(String jobClass, Account account) {
        enqueue(jobClass, objectMapper.createArrayNode().add(globalId(account)));
    }

    private ObjectNode globalId(Object record) {
        try {
            Class<?> modelClass = org.hibernate.Hibernate.getClass(record);
            Object id = modelClass.getMethod("getId").invoke(record);
            if (id == null) {
                throw new IllegalArgumentException("Cannot enqueue an unsaved " + modelClass.getSimpleName());
            }
            return objectMapper.createObjectNode()
                .put("_aj_globalid", "gid://" + GLOBAL_ID_APP + "/" + modelClass.getSimpleName() + "/" + id);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalArgumentException("Active Job argument has no GlobalID", exception);
        }
    }

    private void enqueue(String jobClass, ArrayNode arguments) {
        String jobId = UUID.randomUUID().toString();
        Instant enqueuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant scheduledAt = enqueuedAt;
        ObjectNode payload = objectMapper.createObjectNode()
            .put("job_class", jobClass)
            .put("job_id", jobId)
            .putNull("provider_job_id")
            .put("queue_name", DEFAULT_QUEUE)
            .putNull("priority");
        payload.set("arguments", arguments);
        payload.put("executions", 0);
        payload.set("exception_executions", objectMapper.createObjectNode());
        payload.put("locale", settings.locale());
        payload.put("timezone", "UTC");
        payload.put("enqueued_at", RAILS_ISO_TIME.format(enqueuedAt));
        payload.put("scheduled_at", RAILS_ISO_TIME.format(scheduledAt));

        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Rails Active Job arguments", exception);
        }
        Timestamp createdAt = Timestamp.from(enqueuedAt);
        Long databaseId = jdbcTemplate.queryForObject("""
            INSERT INTO solid_queue_jobs
                (queue_name, class_name, arguments, active_job_id, created_at, updated_at, priority, scheduled_at)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, 0, ?)
            RETURNING id
            """,
            Long.class,
            DEFAULT_QUEUE,
            jobClass,
            serialized,
            jobId,
            createdAt,
            createdAt,
            Timestamp.from(scheduledAt));
        if (databaseId == null) {
            throw new IllegalStateException("Solid Queue job insert did not return an id");
        }
        jdbcTemplate.update("""
            INSERT INTO solid_queue_ready_executions (job_id, queue_name, priority, created_at)
            VALUES (?, ?, 0, ?)
            """, databaseId, DEFAULT_QUEUE, createdAt);
    }
}
