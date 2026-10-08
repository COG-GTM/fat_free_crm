package com.fatfreecrm.service.read;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResource;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.query.InvalidSearchQueryException;
import com.fatfreecrm.service.query.RubyScalars;
import com.fatfreecrm.service.query.SearchableEntities;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code TasksController#index/show/auto_complete} reads: {@code Task.find_all_grouped} buckets,
 * {@code Task.tracked_by} show scope and {@code Task.my} autocomplete, each composed with the AB-268 Task policy.
 */
@Service
public class TaskReadService {

    public static final List<String> VIEWS = List.of("pending", "assigned", "completed");

    static final List<String> DEFAULT_TASK_BUCKET = List.of(
        "overdue", "due_asap", "due_today", "due_tomorrow", "due_this_week", "due_next_week", "due_later");

    static final List<String> DEFAULT_TASK_COMPLETED = List.of(
        "completed_today", "completed_yesterday", "completed_last_week", "completed_this_month",
        "completed_last_month");

    private static final Set<String> DUE_BUCKETS = Set.copyOf(DEFAULT_TASK_BUCKET);

    private final TaskRepository taskRepository;
    private final SettingRepository settingRepository;
    private final AccessPolicy accessPolicy;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources railsResources;
    private final SearchableEntities searchableEntities;
    private final Clock clock;
    private final ZoneId defaultZone;

    public TaskReadService(
        TaskRepository taskRepository,
        SettingRepository settingRepository,
        AccessPolicy accessPolicy,
        RailsJsonWriter jsonWriter,
        RailsResources railsResources,
        SearchableEntities searchableEntities,
        Clock clock,
        @Value("${ffcrm.time-zone:UTC}") String defaultZone
    ) {
        this.taskRepository = taskRepository;
        this.settingRepository = settingRepository;
        this.accessPolicy = accessPolicy;
        this.jsonWriter = jsonWriter;
        this.railsResources = railsResources;
        this.searchableEntities = searchableEntities;
        this.clock = clock;
        this.defaultZone = ZoneId.of(defaultZone);
    }

    /** Rails {@code TasksController#view}: unknown or missing views fall back to {@code pending}. */
    public static String normalizeView(String view) {
        return view != null && VIEWS.contains(view) ? view : VIEWS.get(0);
    }

    /** Bucket zone: an explicit IANA id (the Rails session {@code Time.zone}) or the configured default. */
    public ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException exception) {
            throw new InvalidSearchQueryException("Unknown time zone: " + timeZone, List.of("timeZone"));
        }
    }

    /** Rails {@code Task.find_all_grouped(user, view)}: ordered bucket name to Rails task JSON rows. */
    @Transactional(readOnly = true)
    public Map<String, List<ObjectNode>> buckets(AuthenticatedUser user, String view, ZoneId zone) {
        String normalized = normalizeView(view);
        TaskBucketWindows windows = TaskBucketWindows.at(clock.instant(), zone);
        boolean completed = "completed".equals(normalized);
        Map<String, List<ObjectNode>> buckets = new LinkedHashMap<>();
        for (String bucket : bucketNames(completed ? "task_completed" : "task_bucket",
                completed ? DEFAULT_TASK_COMPLETED : DEFAULT_TASK_BUCKET)) {
            Specification<Task> specification = accessPolicy.accessibleBy(user, Task.class)
                .and(bucketScope(bucket, windows));
            Sort sort;
            if ("assigned".equals(normalized)) {
                specification = specification.and(assignedBy(user.id())).and(pending())
                    .and((root, query, cb) -> cb.isNotNull(root.get("assignedTo")));
                sort = bucketSort(bucket);
            } else if (completed) {
                specification = specification.and(my(user.id()))
                    .and((root, query, cb) -> cb.isNotNull(root.get("completedAt")));
                sort = Sort.by("name").ascending().and(bucketSort(bucket)).and(Sort.by("completedAt").descending());
            } else {
                specification = specification.and(my(user.id())).and(pending());
                sort = Sort.by("name").ascending().and(bucketSort(bucket));
            }
            List<Long> ids = taskRepository.findAll(specification, sort).stream().map(Task::getId).toList();
            buckets.put(bucket, jsonWriter.write(railsResources.task, ids));
        }
        return buckets;
    }

    /** Rails {@code Task.tracked_by(current_user).find(id)}; callers authorize against the AB-268 policy first. */
    @Transactional(readOnly = true)
    public ObjectNode show(AuthenticatedUser user, long id) {
        Specification<Task> specification = accessPolicy.accessibleBy(user, Task.class)
            .and(trackedBy(user.id()))
            .and((root, query, cb) -> cb.equal(root.get("id"), id));
        if (taskRepository.count(specification) == 0) {
            throw new EntityNotFoundException("Task " + id + " is not tracked by the current user");
        }
        return jsonWriter.writeOne(railsResources.task, id);
    }

    /** Rails {@code Task.my(user).text_search(term).ransack(id_not_in:).result.limit(10)}: name, then id. */
    @Transactional(readOnly = true)
    public AutocompleteResult autocomplete(AuthenticatedUser user, String term, Set<Long> excludedIds) {
        Specification<Task> specification = accessPolicy.accessibleBy(user, Task.class).and(my(user.id()));
        String search = term == null ? "" : term;
        specification = specification.and((root, query, cb) ->
            searchableEntities.forClass(Task.class).textSearch().search(root, cb, search));
        if (!excludedIds.isEmpty()) {
            specification = specification.and((root, query, cb) -> cb.not(root.get("id").in(excludedIds)));
        }
        List<AutocompleteResult.Item> items = taskRepository.findAll(
                specification, PageRequest.of(0, 10, Sort.by("name").ascending().and(Sort.by("id").ascending())))
            .stream()
            .map(task -> new AutocompleteResult.Item(task.getId(), task.getName()))
            .toList();
        return new AutocompleteResult(items);
    }

    /** Rails {@code auto_complete_ids_to_exclude(related)}: a bare id, or {@code <asset>/<id>} task ids. */
    @Transactional(readOnly = true)
    public Set<Long> excludedIds(String related) {
        if (related == null || related.isBlank()) {
            return Set.of();
        }
        String[] segments = related.split("/", -1);
        if (segments.length == 1) {
            return Set.of(RubyScalars.toLong(segments[0]));
        }
        RailsResource.RelatedExclusion exclusion = segments.length >= 2
            ? railsResources.task.relatedExclusions().get(segments[0])
            : null;
        return exclusion == null ? Set.of() : exclusion.ids().apply(RubyScalars.toLong(segments[1]));
    }

    /** Rails {@code Task.my}: {@code (user_id = ? AND assigned_to IS NULL) OR assigned_to = ?}. */
    public static Specification<Task> my(long userId) {
        return (root, query, cb) -> cb.or(
            cb.and(cb.equal(root.get("user").get("id"), userId), cb.isNull(root.get("assignedTo"))),
            cb.equal(root.get("assignedTo").get("id"), userId));
    }

    private static Specification<Task> assignedBy(long userId) {
        return (root, query, cb) -> cb.and(
            cb.equal(root.get("user").get("id"), userId),
            cb.isNotNull(root.get("assignedTo")),
            cb.notEqual(root.get("assignedTo").get("id"), userId));
    }

    private static Specification<Task> trackedBy(long userId) {
        return (root, query, cb) -> cb.or(
            cb.equal(root.get("user").get("id"), userId),
            cb.equal(root.get("assignedTo").get("id"), userId));
    }

    private static Specification<Task> pending() {
        return (root, query, cb) -> cb.isNull(root.get("completedAt"));
    }

    private static Sort bucketSort(String bucket) {
        return DUE_BUCKETS.contains(bucket) ? Sort.by("id").descending() : Sort.unsorted();
    }

    private static Specification<Task> bucketScope(String bucket, TaskBucketWindows w) {
        return switch (bucket) {
            case "due_asap" -> (root, query, cb) ->
                cb.and(cb.isNull(root.get("dueAt")), cb.equal(root.get("bucket"), "due_asap"));
            case "overdue" -> (root, query, cb) ->
                cb.and(cb.isNotNull(root.get("dueAt")), cb.lessThan(root.get("dueAt"), w.midnight()));
            case "due_today" -> range("dueAt", w.midnight(), w.tomorrow());
            case "due_tomorrow" -> range("dueAt", w.tomorrow(), w.dayAfterTomorrow());
            case "due_this_week" -> range("dueAt", w.dayAfterTomorrow(), w.nextWeek());
            case "due_next_week" -> range("dueAt", w.nextWeek(), w.afterNextWeek());
            case "due_later" -> (root, query, cb) -> cb.or(
                cb.and(cb.isNull(root.get("dueAt")), cb.equal(root.get("bucket"), "due_later")),
                cb.greaterThanOrEqualTo(root.get("dueAt"), w.afterNextWeek()));
            case "completed_today" -> range("completedAt", w.midnight(), w.tomorrow());
            case "completed_yesterday" -> range("completedAt", w.yesterday(), w.midnight());
            case "completed_this_week" -> range("completedAt", w.beginningOfWeek(), w.yesterday());
            case "completed_last_week" -> range("completedAt", w.beginningOfLastWeek(), w.beginningOfWeek());
            case "completed_this_month" -> range("completedAt", w.beginningOfMonth(), w.beginningOfLastWeek());
            case "completed_last_month" -> range("completedAt", w.beginningOfLastMonth(), w.beginningOfMonth());
            // Rails sends the Setting key to Task as a scope name; unknown keys raise NoMethodError (500).
            default -> throw new IllegalStateException("Unknown task bucket setting: " + bucket);
        };
    }

    private static Specification<Task> range(String attribute, Instant from, Instant until) {
        return (Root<Task> root, jakarta.persistence.criteria.CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            Path<Instant> path = root.get(attribute);
            Predicate lower = cb.greaterThanOrEqualTo(path, from);
            return cb.and(lower, cb.lessThan(path, until));
        };
    }

    private List<String> bucketNames(String setting, List<String> defaults) {
        return settingRepository.findByName(setting)
            .map(Setting::getValue)
            .map(value -> {
                try {
                    List<String> names = RailsYaml.readStringList(value).stream()
                        .map(name -> name.startsWith(":") ? name.substring(1) : name)
                        .toList();
                    return names.isEmpty() ? defaults : names;
                } catch (IllegalArgumentException exception) {
                    return defaults;
                }
            })
            .orElse(defaults);
    }
}
