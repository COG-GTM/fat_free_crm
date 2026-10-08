package com.fatfreecrm.service.write;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.authz.AccessPolicy;
import com.fatfreecrm.security.authz.CrmAccessPolicy;
import com.fatfreecrm.service.audit.EntityAttributes;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.validation.ActiveModelMessages;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.domain.Comment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code CommentsController} writes and the {@code Comment} model callbacks:
 *
 * <ul>
 *   <li>create: {@code Comment.new(comment_params.merge(user_id: current_user.id))}; the
 *   commentable must exist inside {@code model.my(current_user)} scope → 404 via
 *   {@code respond_to_related_not_found}; before_create {@code subscribe_mentioned_users}
 *   ({@code /@([a-zA-Z0-9_-]+)/} + {@code User.find_by_username}) then after_create
 *   {@code subscribe_user_to_entity} (appends the author id, duplicates allowed) — each
 *   {@code commentable.subscribed_users += id} change persists via {@code commentable.save}, which
 *   bumps commentable {@code updated_at} and re-runs the commentable's own save callbacks
 *   ({@code set_due_date} on an uncompleted Task); {@code notify_subscribers} mailer is skipped
 *   (AB-273 jobs-mail). The commentable row is locked FOR UPDATE before appending.</li>
 *   <li>update/destroy: CanCan owner-or-admin via {@code hasPermission}; update permits
 *   {@code user_id}/{@code commentable_*} reassignment (Rails gap — mirrored, flagged).</li>
 *   <li>PaperTrail {@code meta: {related: :commentable}, ignore: [:state]}.</li>
 * </ul>
 */
@Service
public class CommentWriteService {

    private static final Pattern MENTION = Pattern.compile("@([a-zA-Z0-9_-]+)");

    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final VersionRecorder versionRecorder;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources railsResources;
    private final ActiveModelMessages messages;
    private final EntityManager entityManager;
    private final TaskWriteService taskWriteService;

    public CommentWriteService(
        CommentRepository commentRepository,
        UserRepository userRepository,
        AccessPolicy accessPolicy,
        VersionRecorder versionRecorder,
        RailsJsonWriter jsonWriter,
        RailsResources railsResources,
        ActiveModelMessages messages,
        EntityManager entityManager,
        TaskWriteService taskWriteService
    ) {
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.versionRecorder = versionRecorder;
        this.jsonWriter = jsonWriter;
        this.railsResources = railsResources;
        this.messages = messages;
        this.entityManager = entityManager;
        this.taskWriteService = taskWriteService;
    }

    /** {@code POST /comments}: commentable must be inside {@code my(current_user)} scope → 404. */
    @Transactional
    public ObjectNode create(AuthenticatedUser user, RailsParams params) {
        Comment comment = new Comment();
        apply(comment, params);
        comment.setUser(userRepository.findById(user.id()).orElse(null));
        // Rails checks the commentable before @comment.save (and its validations).
        Object commentable = findCommentable(user, comment.getCommentableType(),
            comment.getCommentableId());
        if (commentable == null) {
            throw new EntityNotFoundException("Commentable was not found");
        }
        validate(comment);
        // before_create subscribe_mentioned_users; after_create subscribe_user_to_entity.
        subscribeMentions(user, commentable, comment.getComment());
        subscribe(user, commentable, user.id());
        comment = commentRepository.saveAndFlush(comment);
        versionRecorder.recordCreate(user, comment, EntityAttributes.of(comment), Map.of());
        return jsonWriter.writeOne(railsResources.comment, comment.getId());
    }

    /** {@code PUT /comments/:id}: {@code @comment.update(comment_params)} (permitted keys only). */
    @Transactional
    public void update(AuthenticatedUser user, long id, RailsParams params) {
        Comment comment = commentRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Comment " + id + " was not found"));
        Map<String, Object> before = EntityAttributes.of(comment);
        apply(comment, params);
        validate(comment);
        comment = commentRepository.saveAndFlush(comment);
        versionRecorder.recordUpdate(user, comment, before, EntityAttributes.of(comment),
            params.keys());
    }

    @Transactional
    public void destroy(AuthenticatedUser user, long id) {
        Comment comment = commentRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Comment " + id + " was not found"));
        versionRecorder.recordDestroy(user, comment, EntityAttributes.of(comment));
        commentRepository.delete(comment);
    }

    private void apply(Comment comment, RailsParams params) {
        if (params.provided("user_id")) {
            Integer id = RailsParams.asInteger(params.get("user_id").orElse(null));
            comment.setUser(id == null ? null : userRepository.findById(id.longValue()).orElse(null));
        }
        params.assignString("commentable_type", comment::setCommentableType);
        params.assignInteger("commentable_id", comment::setCommentableId);
        params.assignBoolean("private", comment::setPrivate);
        params.assignString("title", comment::setTitle);
        params.assignString("comment", comment::setComment);
        params.assignString("state", comment::setState);
    }

    /** Rails {@code comment} presence validation → {@code "can't be blank"}. */
    private void validate(Comment comment) {
        RailsErrors errors = new RailsErrors();
        if (comment.getComment() == null || comment.getComment().isBlank()) {
            errors.add(messages, "comment", "comment", "blank");
        }
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    /**
     * {@code model.my(current_user).find_by_id(id)}: Task scopes to {@code Task.my}
     * (owner-unassigned or assignee), User scopes to self, CRM entities scope to
     * {@code accessible_by}. Unknown types mirror Rails' {@code find_class} raise (400 documented
     * deviation — Rails answers 500).
     */
    private Object findCommentable(AuthenticatedUser user, String type, Integer id) {
        RailsModelType modelType = RailsModelType.fromRailsName(type)
            .orElseThrow(() -> new IllegalArgumentException("Unknown commentable type: " + type));
        if (id == null || !CrmAccessPolicy.supports(modelType.entityClass())) {
            return null;
        }
        Class<?> entityClass = modelType.entityClass();
        Object entity = entityManager.find(entityClass, id.longValue());
        if (entity == null) {
            return null;
        }
        if (entity instanceof Task task) {
            return myTask(task, user.id()) ? task : null;
        }
        if (entity instanceof User commentableUser) {
            return commentableUser.getId().equals(user.id()) ? entity : null;
        }
        return inScope(entity, user) ? entity : null;
    }

    @SuppressWarnings("unchecked")
    private Specification<Object> accessibleSpec(AuthenticatedUser user, Class<?> type) {
        return accessPolicy.accessibleBy(user, (Class<Object>) type);
    }

    /** Rails {@code Task.my}: {@code (user_id = ? AND assigned_to IS NULL) OR assigned_to = ?}. */
    private static boolean myTask(Task task, Long userId) {
        Long owner = task.getUser() == null ? null : task.getUser().getId();
        Long assignee = task.getAssignedTo() == null ? null : task.getAssignedTo().getId();
        return (userId.equals(owner) && assignee == null) || userId.equals(assignee);
    }

    @SuppressWarnings("unchecked")
    private boolean inScope(Object entity, AuthenticatedUser user) {
        Specification<Object> spec = accessibleSpec(user, entity.getClass());
        Long id = ((BaseEntity) entity).getId();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Object> root = (Root<Object>) query.from(entity.getClass());
        query.select(cb.count(root)).where(
            cb.equal(root.get("id"), id),
            spec.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getSingleResult() > 0;
    }

    /** {@code subscribe_mentioned_users}: append each @-mentioned user's id, then save. */
    private void subscribeMentions(AuthenticatedUser user, Object commentable, String body) {
        if (body == null) {
            return;
        }
        Matcher matcher = MENTION.matcher(body);
        while (matcher.find()) {
            String username = matcher.group(1);
            userRepository.findByLogin(username.toLowerCase(java.util.Locale.ROOT), Limit.of(1))
                .stream()
                .filter(candidate -> username.equals(candidate.getUsername()))
                .findFirst()
                .ifPresent(mentioned -> subscribe(user, commentable, mentioned.getId()));
        }
    }

    /**
     * Append {@code userId} to {@code commentable.subscribed_users} and persist through a FOR
     * UPDATE lock — mirrors {@code commentable.save}: bumps {@code updated_at}, runs the
     * commentable's save callbacks ({@code set_due_date} on uncompleted Tasks) and writes a version
     * when non-ignored attributes changed ({@code subscribed_users} is ignored everywhere;
     * {@code updated_at} still makes the change notable).
     */
    private void subscribe(AuthenticatedUser user, Object commentable, Long userId) {
        if (subscribedUsers(commentable) == null) {
            return; // entity has no subscribed_users column (e.g. User)
        }
        Object locked = entityManager.find(commentable.getClass(),
            ((BaseEntity) commentable).getId(), LockModeType.PESSIMISTIC_WRITE);
        if (locked instanceof Task task && !commentableSaveValid(task)) {
            return; // commentable.save returned false in Rails: subscribed_users is not persisted
        }
        Map<String, Object> before = attributesOf(locked);
        subscribedUsers(locked).add(userId);
        if (locked instanceof Task task && task.getCompletedAt() == null) {
            taskWriteService.applySetDueDate(task);
        }
        entityManager.flush();
        versionRecorder.recordUpdate(user, locked, before, attributesOf(locked),
            java.util.List.of());
    }

    /**
     * Attribute dump for the version rows a subscription write produces. Task/Comment/Email have
     * hand-mapped column-order dumps; every other commentable is read back as a {@code SELECT *}
     * row (PostgreSQL ordinal order matches the Rails column order PaperTrail dumps).
     */
    private Map<String, Object> attributesOf(Object entity) {
        return switch (entity) {
            case Task task -> EntityAttributes.of(task);
            case Comment comment -> EntityAttributes.of(comment);
            case com.fatfreecrm.domain.Email email -> EntityAttributes.of(email);
            case com.fatfreecrm.domain.SavedList list -> EntityAttributes.of(list);
            default -> rowAttributes(entity);
        };
    }

    private Map<String, Object> rowAttributes(Object entity) {
        String table = entity.getClass()
            .getAnnotation(jakarta.persistence.Table.class).name();
        Long id = ((BaseEntity) entity).getId();
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            Map<String, Object> attributes = new java.util.LinkedHashMap<>();
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM " + table + " WHERE id = ?")) {
                statement.setLong(1, id);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    java.sql.ResultSetMetaData metadata = rows.getMetaData();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        String column = metadata.getColumnLabel(index);
                        Object value = rows.getObject(index);
                        if ("subscribed_users".equals(column) && value != null) {
                            value = parseSubscribedIds(value.toString());
                        } else if (value instanceof java.sql.Timestamp timestamp) {
                            value = timestamp.toInstant();
                        }
                        attributes.put(column, value);
                    }
                }
            }
            return attributes;
        });
    }

    /** Unmarshal the {@code "---\n- N\n- M\n"} subscribed_users column back to a list. */
    private static List<Long> parseSubscribedIds(String yaml) {
        List<Long> ids = new java.util.ArrayList<>();
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("- \\d+")) {
                ids.add(Long.valueOf(trimmed.substring(2)));
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    /**
     * The validations {@code commentable.save} runs on a Task: a {@code specific_time} task still
     * runs {@code Time.parse(nil)} and raises TypeError (Rails 500 → {@link RailsInternalError});
     * a blank name or missing user makes save return false and the subscription is not persisted.
     */
    private boolean commentableSaveValid(Task task) {
        boolean completed = task.getCompletedAt() != null;
        if ("specific_time".equals(task.getBucket()) && !completed) {
            throw new RailsInternalError("no implicit conversion of nil into String");
        }
        return task.getUser() != null && task.getName() != null && !task.getName().isBlank();
    }

    private static List<Long> subscribedUsers(Object entity) {
        return switch (entity) {
            case Task task -> task.getSubscribedUsers();
            case com.fatfreecrm.domain.Account account -> account.getSubscribedUsers();
            case com.fatfreecrm.domain.Campaign campaign -> campaign.getSubscribedUsers();
            case com.fatfreecrm.domain.Contact contact -> contact.getSubscribedUsers();
            case com.fatfreecrm.domain.Lead lead -> lead.getSubscribedUsers();
            case com.fatfreecrm.domain.Opportunity opportunity -> opportunity.getSubscribedUsers();
            default -> null;
        };
    }
}
