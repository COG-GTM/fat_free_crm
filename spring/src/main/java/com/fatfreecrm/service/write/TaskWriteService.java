package com.fatfreecrm.service.write;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.customfields.CustomFieldWriteService;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.audit.EntityAttributes;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.validation.ActiveModelMessages;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code TasksController} writes: create ({@code Task.new(task_params)} — no forced
 * {@code user_id}, no authorization; Rails gap mirrored and flagged), update/destroy/complete/
 * uncomplete scoped to {@code Task.tracked_by(current_user)} (404 outside the scope, applied after
 * the {@code hasPermission} check), and the model callbacks
 * {@code set_due_date}/{@code specific_time}/presence validations in declaration order.
 * TaskObserver {@code complete}/{@code reassign}/{@code reschedule} versions and PaperTrail
 * {@code meta: {related: :asset}, ignore: [:subscribed_users]} are recorded via
 * {@link VersionRecorder}. {@code bucket}/{@code view} request fields are UI hints Rails ignores.
 *
 * <p>{@code calendar} parsing mirrors Ruby {@code Time.parse} = process-local zone: the JVM
 * default zone (documented in the ADR; CI is UTC). {@code due_this_week}/{@code due_next_week}
 * boundaries use the request {@code timeZone} param (AB-270 {@code ffcrm.time-zone} default).
 */
@Service
public class TaskWriteService {

    private static final ZoneId JVM_ZONE = ZoneId.systemDefault();
    private static final String SPECIFIC_TIME = "specific_time";

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final CustomFieldWriteService customFieldWriteService;
    private final VersionRecorder versionRecorder;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources railsResources;
    private final ActiveModelMessages messages;
    private final Clock clock;
    private final ZoneId defaultZone;

    public TaskWriteService(
        TaskRepository taskRepository,
        UserRepository userRepository,
        CustomFieldWriteService customFieldWriteService,
        VersionRecorder versionRecorder,
        RailsJsonWriter jsonWriter,
        RailsResources railsResources,
        ActiveModelMessages messages,
        Clock clock,
        @Value("${ffcrm.time-zone:UTC}") String defaultZone
    ) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.customFieldWriteService = customFieldWriteService;
        this.versionRecorder = versionRecorder;
        this.jsonWriter = jsonWriter;
        this.railsResources = railsResources;
        this.messages = messages;
        this.clock = clock;
        this.defaultZone = ZoneId.of(defaultZone);
    }

    /** Rails {@code POST /tasks}: {@code Task.new(task_params)} + validations + set_due_date. */
    @Transactional
    public ObjectNode create(AuthenticatedUser user, RailsParams params, ZoneId zone) {
        Task task = new Task();
        String calendar = apply(task, params);
        validate(task, calendar);
        task.setDueAt(dueAtFor(task, calendar, zone == null ? defaultZone : zone));
        task = taskRepository.saveAndFlush(task);
        // Custom fields persist via their own UPDATE once the row has an id (AB-271 mechanism).
        customFieldWriteService.write(task, customFieldInput(params));
        task = taskRepository.saveAndFlush(task);
        Map<String, Object> attributes = EntityAttributes.of(task);
        versionRecorder.recordCreate(user, task, attributes, TASK_DEFAULTS);
        return jsonWriter.writeOne(railsResources.task, task.getId());
    }

    /** Rails {@code PUT /tasks/:id}: tracked_by find → update(task_params). */
    @Transactional
    public void update(AuthenticatedUser user, long id, RailsParams params, ZoneId zone) {
        Task task = trackedTask(user.id(), id);
        Map<String, Object> before = EntityAttributes.of(task);
        Task original = snapshot(task);
        String calendar = apply(task, params);
        validate(task, calendar);
        if (task.getCompletedAt() == null) {
            task.setDueAt(dueAtFor(task, calendar, zone == null ? defaultZone : zone));
        }
        task = taskRepository.saveAndFlush(task);
        customFieldWriteService.write(task, customFieldInput(params));
        task = taskRepository.saveAndFlush(task);
        recordUpdateVersions(user, task, before, original,
            params.keys());
    }

    /** Rails {@code PUT /tasks/:id/complete}: update(completed_at: now, completed_by: user). */
    @Transactional
    public void complete(AuthenticatedUser user, long id) {
        Task task = trackedTask(user.id(), id);
        Map<String, Object> before = EntityAttributes.of(task);
        Task original = snapshot(task);
        task.setCompletedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        task.setCompletedBy(userRepository.findById(user.id()).orElse(null));
        // set_due_date is skipped when completed? is true; validations also skip specific_time.
        validate(task, null);
        task = taskRepository.saveAndFlush(task);
        recordUpdateVersions(user, task, before, original,
            java.util.List.of("completed_at", "completed_by"));
    }

    /** Rails {@code PUT /tasks/:id/uncomplete}: update(completed_at: nil, completed_by: nil). */
    @Transactional
    public void uncomplete(AuthenticatedUser user, long id, ZoneId zone) {
        Task task = trackedTask(user.id(), id);
        Map<String, Object> before = EntityAttributes.of(task);
        Task original = snapshot(task);
        task.setCompletedAt(null);
        task.setCompletedBy(null);
        validate(task, null);
        task.setDueAt(dueAtFor(task, null, zone == null ? defaultZone : zone));
        task = taskRepository.saveAndFlush(task);
        recordUpdateVersions(user, task, before, original,
            java.util.List.of("completed_at", "completed_by"));
    }

    @Transactional
    public void destroy(AuthenticatedUser user, long id) {
        Task task = trackedTask(user.id(), id);
        Map<String, Object> attributes = EntityAttributes.of(task);
        versionRecorder.recordDestroy(user, task, attributes);
        taskRepository.delete(task);
    }

    private Task trackedTask(Long userId, long id) {
        Specification<Task> specification = (root, query, cb) -> cb.equal(root.get("id"), id);
        specification = specification.and((root, query, cb) -> cb.or(
            cb.equal(root.get("user").get("id"), userId),
            cb.equal(root.get("assignedTo").get("id"), userId)));
        return taskRepository.findOne(specification)
            .orElseThrow(() -> new EntityNotFoundException("Task " + id + " was not found"));
    }

    /**
     * {@code assign_attributes(task_params)}: only provided keys are assigned. {@code user_id} is
     * permitted by Rails (gap — mirrored, see ADR open questions); {@code calendar} is the transient
     * accessor, returned for the validators/set_due_date.
     */
    private String apply(Task task, RailsParams params) {
        if (params.provided("user_id")) {
            task.setUser(lookupUser(RailsParams.asInteger(params.get("user_id").orElse(null))));
        }
        if (params.provided("assigned_to")) {
            task.setAssignedTo(lookupUser(RailsParams.asInteger(params.get("assigned_to").orElse(null))));
        }
        if (params.provided("completed_by")) {
            task.setCompletedBy(lookupUser(RailsParams.asInteger(params.get("completed_by").orElse(null))));
        }
        params.assignString("name", task::setName);
        params.assignString("asset_type", task::setAssetType);
        params.assignInteger("asset_id", task::setAssetId);
        params.assignString("priority", task::setPriority);
        params.assignString("category", task::setCategory);
        params.assignString("bucket", task::setBucket);
        params.assignInstant("due_at", task::setDueAt);
        params.assignInstant("completed_at", task::setCompletedAt);
        params.assignInstant("deleted_at", task::setDeletedAt);
        params.assignString("background_info", task::setBackgroundInfo);
        return params.provided("calendar")
            ? RailsParams.asString(params.get("calendar").orElse(null))
            : null;
    }

    private User lookupUser(Integer id) {
        return id == null ? null : userRepository.findById(id.longValue()).orElse(null);
    }

    /**
     * Rails validation order: presence user (belongs_to "must exist" + validates_presence "can't be
     * blank"), presence name ({@code :missing_task_name}), presence calendar (specific_time and not
     * completed), then {@code validate :specific_time} unless completed. {@code Time.parse(nil)}
     * raises TypeError in Rails — mirrored as a 500 via {@link RailsInternalError}.
     */
    private void validate(Task task, String calendar) {
        RailsErrors errors = new RailsErrors();
        if (task.getUser() == null) {
            errors.add(messages, "task", "user", "required");
            errors.add(messages, "task", "user", "blank");
        }
        if (task.getName() == null || task.getName().isBlank()) {
            errors.add(messages, "task", "name", "missing_task_name");
        }
        boolean completed = task.getCompletedAt() != null;
        if (SPECIFIC_TIME.equals(task.getBucket()) && !completed) {
            if (calendar == null || calendar.isBlank()) {
                errors.add(messages, "task", "calendar", "blank");
            }
            if (calendar == null) {
                // Time.parse(nil) raises TypeError in the :specific_time validator.
                throw new RailsInternalError("no implicit conversion of nil into String");
            }
            try {
                parseCalendar(calendar);
            } catch (DateTimeParseException exception) {
                errors.add(messages, "task", "calendar", "invalid_date");
            }
        }
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    /**
     * {@code before_create/before_update :set_due_date}: recomputes {@code due_at} from the bucket
     * in the request zone ({@code Time.zone}); {@code specific_time} uses {@code calendar ? parse :
     * nil}; {@code overdue} keeps the existing due_at or yesterday-midnight.
     */
    /**
     * {@code set_due_date} as invoked by a commentable {@code save} (nil {@code calendar} →
     * {@code specific_time} writes {@code due_at = nil}), in the configured default zone.
     */
    public void applySetDueDate(Task task) {
        task.setDueAt(dueAtFor(task, null, defaultZone));
    }

    private Instant dueAtFor(Task task, String calendar, ZoneId zone) {
        String bucket = task.getBucket();
        if (bucket == null) {
            return null;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDateTime midnight = today.atStartOfDay();
        return switch (bucket) {
            case "overdue" -> task.getDueAt() != null
                ? task.getDueAt()
                : midnight.minusDays(1).atZone(zone).toInstant();
            case "due_today" -> midnight.atZone(zone).toInstant();
            case "due_tomorrow" -> midnight.plusDays(1).atZone(zone).toInstant();
            case "due_this_week" -> endOfWeek(today, zone);
            case "due_next_week" -> nextWeekEndOfWeek(today, zone);
            case "due_later" -> midnight.plusYears(100).atZone(zone).toInstant();
            case SPECIFIC_TIME -> calendar == null ? null : parseCalendar(calendar);
            default -> null;
        };
    }

    private static Instant endOfWeek(LocalDate today, ZoneId zone) {
        LocalDate end = today.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY));
        return LocalDateTime.of(end, LocalTime.of(23, 59, 59, 999_999_000)).atZone(zone).toInstant();
    }

    private static Instant nextWeekEndOfWeek(LocalDate today, ZoneId zone) {
        LocalDate nextWeekStart = today.with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        LocalDate end = nextWeekStart.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY));
        return LocalDateTime.of(end, LocalTime.of(23, 59, 59, 999_999_000)).atZone(zone).toInstant();
    }

    /**
     * Ruby {@code Time.parse}: accepts ISO-ish datetimes in the process-local zone — the JVM
     * default zone here (Rails dev/prod processes run in the container's TZ; CI is UTC).
     */
    static Instant parseCalendar(String calendar) {
        String text = calendar.trim();
        try {
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(JVM_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(JVM_ZONE).toInstant();
        } catch (DateTimeParseException exception) {
            throw exception;
        }
    }

    /**
     * PaperTrail update + TaskObserver: one {@code update} version when non-ignored attributes
     * changed, then an observer {@code complete}/{@code reassign}/{@code reschedule} row keyed off
     * the before/after comparison (priority order matches the observer's early returns).
     */
    private void recordUpdateVersions(AuthenticatedUser user, Task task, Map<String, Object> before,
        Task original, java.util.List<String> assignedOrder) {
        Map<String, Object> after = EntityAttributes.of(task);
        versionRecorder.recordUpdate(user, task, before, after, assignedOrder);
        if (task.getCompletedAt() != null && original.getCompletedAt() == null) {
            versionRecorder.recordEvent(user, RailsModelType.TASK, task.getId(), "complete");
        } else if (!java.util.Objects.equals(
            idOf(task.getAssignedTo()), idOf(original.getAssignedTo()))) {
            versionRecorder.recordEvent(user, RailsModelType.TASK, task.getId(), "reassign");
        } else if (!java.util.Objects.equals(task.getBucket(), original.getBucket())) {
            versionRecorder.recordEvent(user, RailsModelType.TASK, task.getId(), "reschedule");
        }
    }

    private static Long idOf(User user) {
        return user == null ? null : user.getId();
    }

    private static Task snapshot(Task task) {
        Task copy = new Task();
        copy.setCompletedAt(task.getCompletedAt());
        copy.setBucket(task.getBucket());
        copy.setAssignedTo(task.getAssignedTo());
        return copy;
    }

    private static Map<String, Object> customFieldInput(RailsParams params) {
        Map<String, Object> input = new LinkedHashMap<>();
        paramsCustomFieldKeys(params).forEach(key ->
            input.put(key, RailsParams.asString(params.get(key).orElse(null)) == null
                ? null : jsonValue(params.get(key).get())));
        return input;
    }

    private static java.util.List<String> paramsCustomFieldKeys(RailsParams params) {
        return params == null ? java.util.List.of() : params.keys().stream()
            .filter(key -> key.startsWith("cf_"))
            .toList();
    }

    private static Object jsonValue(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node;
    }

    /** Task column defaults that drive the create object_changeset [default, value] pairs. */
    static final Map<String, Object> TASK_DEFAULTS = defaultTaskDefaults();

    private static Map<String, Object> defaultTaskDefaults() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("name", "");
        return defaults;
    }
}
