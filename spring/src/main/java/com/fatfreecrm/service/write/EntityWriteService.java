package com.fatfreecrm.service.write;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.customfields.CustomFieldWriteService;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.AccountOpportunity;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Campaign;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.ContactOpportunity;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.Opportunity;
import com.fatfreecrm.domain.Setting;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.Access;
import com.fatfreecrm.domain.support.CrmEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountOpportunityRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.fatfreecrm.repository.CampaignRepository;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.ContactOpportunityRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.repository.OpportunityRepository;
import com.fatfreecrm.repository.PermissionRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.TaskRepository;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.domain.Specification;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Rails {@code EntitiesController} JSON writes for accounts, campaigns, contacts, opportunities
 * and leads (AB-272 Phase B). {@code resource_params} is {@code permit!} — every model key is
 * assignable, including {@code user_ids}/{@code group_ids}/{@code tag_list}/{@code cf_*}.
 *
 * <p>Mirrored quirks (verified live, see ADR): CanCan {@code attributes_for} supplies
 * {@code access: 'Public', user_id: current_user, assigned_to: current_user} only for keys absent
 * from the payload; permission row deletions by {@code access=}/{@code user_ids=}/{@code group_ids=}
 * commit immediately (REQUIRES_NEW) even when the entity save then fails 422; subscribe persists
 * then 500s via the {@code respond_with(@entity)} nil-ivar bug; opportunity create without an
 * {@code account} param 500s on {@code params[:account][:id]}; promote is not transactional and a
 * failed promote 500s on {@code errors + errors}; counter writes go through update_counters
 * (no updated_at bump, no version); join-table attach writes AccountContact create versions with
 * meta {@code related: :contact} and counter-cache increments.
 */
@Service
public class EntityWriteService {

    private static final Pattern RUBY_INTEGER = Pattern.compile("^\\s*([+-]?\\d+)");

    private final AccountRepository accountRepository;
    private final CampaignRepository campaignRepository;
    private final ContactRepository contactRepository;
    private final LeadRepository leadRepository;
    private final OpportunityRepository opportunityRepository;
    private final TaskRepository taskRepository;
    private final CommentRepository commentRepository;
    private final AddressRepository addressRepository;
    private final AccountContactRepository accountContactRepository;
    private final AccountOpportunityRepository accountOpportunityRepository;
    private final ContactOpportunityRepository contactOpportunityRepository;
    private final TagRepository tagRepository;
    private final TaggingRepository taggingRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final SettingRepository settingRepository;
    private final CustomFieldWriteService customFieldWriteService;
    private final CommentWriteService commentWriteService;
    private final VersionRecorder versionRecorder;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources railsResources;
    private final ActiveModelMessages messages;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNew;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public EntityWriteService(
        AccountRepository accountRepository,
        CampaignRepository campaignRepository,
        ContactRepository contactRepository,
        LeadRepository leadRepository,
        OpportunityRepository opportunityRepository,
        TaskRepository taskRepository,
        CommentRepository commentRepository,
        AddressRepository addressRepository,
        AccountContactRepository accountContactRepository,
        AccountOpportunityRepository accountOpportunityRepository,
        ContactOpportunityRepository contactOpportunityRepository,
        TagRepository tagRepository,
        TaggingRepository taggingRepository,
        PermissionRepository permissionRepository,
        UserRepository userRepository,
        SettingRepository settingRepository,
        CustomFieldWriteService customFieldWriteService,
        CommentWriteService commentWriteService,
        VersionRecorder versionRecorder,
        RailsJsonWriter jsonWriter,
        RailsResources railsResources,
        ActiveModelMessages messages,
        EntityManager entityManager,
        AccessPolicy accessPolicy,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.campaignRepository = campaignRepository;
        this.contactRepository = contactRepository;
        this.leadRepository = leadRepository;
        this.opportunityRepository = opportunityRepository;
        this.taskRepository = taskRepository;
        this.commentRepository = commentRepository;
        this.addressRepository = addressRepository;
        this.accountContactRepository = accountContactRepository;
        this.accountOpportunityRepository = accountOpportunityRepository;
        this.contactOpportunityRepository = contactOpportunityRepository;
        this.tagRepository = tagRepository;
        this.taggingRepository = taggingRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
        this.settingRepository = settingRepository;
        this.customFieldWriteService = customFieldWriteService;
        this.commentWriteService = commentWriteService;
        this.versionRecorder = versionRecorder;
        this.jsonWriter = jsonWriter;
        this.railsResources = railsResources;
        this.messages = messages;
        this.entityManager = entityManager;
        this.accessPolicy = accessPolicy;
        this.jdbcTemplate = jdbcTemplate;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        this.clock = clock;
    }

    // ------------------------------------------------------------------ row attributes

    /**
     * Attribute dump via {@code SELECT *} in PostgreSQL ordinal order — the same column order
     * Rails/PaperTrail dumps ({@code subscribed_users} unmarshalled to an id list like the Rails
     * serialize column).
     */
    private Map<String, Object> rowAttributes(Class<?> entityClass, Long id) {
        String table = entityClass.getAnnotation(jakarta.persistence.Table.class).name();
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            Map<String, Object> attributes = new LinkedHashMap<>();
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
            // Rails serializes the acts_as_taggable tag_list virtual attribute and never sees the
            // Spring-only custom_fields column (added after its schema load).
            if (TAGGABLE_ENTITIES.contains(entityClass)) {
                attributes.remove("custom_fields");
                List<String> tagList = new ArrayList<>();
                try (java.sql.PreparedStatement tags = connection.prepareStatement(
                        "SELECT t.name FROM taggings tg JOIN tags t ON t.id = tg.tag_id "
                            + "WHERE tg.taggable_type = ? AND tg.taggable_id = ? "
                            + "AND tg.context = 'tags' ORDER BY tg.id")) {
                    tags.setString(1, entityClass.getSimpleName());
                    tags.setLong(2, id);
                    try (java.sql.ResultSet rows = tags.executeQuery()) {
                        while (rows.next()) {
                            tagList.add(rows.getString(1));
                        }
                    }
                }
                attributes.put("tag_list", tagList);
            }
            return attributes;
        });
    }

    private static final java.util.Set<Class<?>> TAGGABLE_ENTITIES = java.util.Set.of(
        Account.class, Campaign.class, Contact.class, Lead.class, Opportunity.class);

    // DB column defaults — PaperTrail's create object_changes omits attrs equal to their default
    // (verified live). tag_list's initial value is the empty tag list.
    private static final Map<String, Object> ACCOUNT_DEFAULTS = Map.of(
        "name", "", "access", "Public", "rating", 0, "contacts_count", 0,
        "opportunities_count", 0, "tag_list", List.of());
    private static final Map<String, Object> CAMPAIGN_DEFAULTS = Map.of(
        "name", "", "access", "Public", "tag_list", List.of());
    private static final Map<String, Object> CONTACT_DEFAULTS = Map.of(
        "first_name", "", "last_name", "", "access", "Public", "do_not_call", false,
        "tag_list", List.of());
    private static final Map<String, Object> LEAD_DEFAULTS = Map.of(
        "first_name", "", "last_name", "", "access", "Public", "rating", 0,
        "do_not_call", false, "tag_list", List.of());
    private static final Map<String, Object> OPPORTUNITY_DEFAULTS = Map.of(
        "name", "", "access", "Public", "tag_list", List.of());

    private static List<Long> parseSubscribedIds(String yaml) {
        List<Long> ids = new ArrayList<>();
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("- \\d+")) {
                ids.add(Long.valueOf(trimmed.substring(2)));
            }
        }
        return ids.isEmpty() ? null : ids;
    }

    // ------------------------------------------------------------------ params helpers

    /** Convert a JSON object node to the ordered map {@link RailsParams} wraps. */
    static Map<String, JsonNode> toMap(JsonNode node) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue()));
        }
        return map;
    }

    private static String str(JsonNode node) {
        return RailsParams.asString(node);
    }

    private static Integer integer(JsonNode node) {
        return RailsParams.asInteger(node);
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText().trim());
        } catch (NumberFormatException exception) {
            return null; // Rails decimal cast of garbage → nil
        }
    }

    private static LocalDate date(JsonNode node) {
        String text = str(node);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
        } catch (java.time.format.DateTimeParseException exception) {
            return null;
        }
    }

    private User userRef(Integer id) {
        return id == null ? null : userRepository.findById(id.longValue()).orElse(null);
    }

    private List<Object> listValue(JsonNode node) {
        List<Object> values = new ArrayList<>();
        if (node == null || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> values.add(item.isNumber() ? item.numberValue() : item.asText()));
        } else if (node.isTextual()) {
            // Rails tag_list/user_ids also accept comma-joined strings.
            for (String piece : node.asText().split(",")) {
                values.add(piece);
            }
        } else {
            values.add(node.isNumber() ? node.numberValue() : node.asText());
        }
        return values;
    }

    // ------------------------------------------------------------------ permissions

    /**
     * Permission rows pending insert, mirroring Rails {@code permissions.build}: they persist only
     * when the owner save succeeds. Deletes execute in REQUIRES_NEW so they outlive a 422.
     */
    private static final class PendingPermissions {
        final List<Long> users = new ArrayList<>();
        final List<Long> groups = new ArrayList<>();
    }

    private void deletePermissionsImmediate(String assetType, Long assetId) {
        if (assetId == null) {
            return;
        }
        requiresNew.executeWithoutResult(status ->
            jdbcTemplate.update("DELETE FROM permissions WHERE asset_type = ? AND asset_id = ?",
                assetType, assetId.intValue()));
    }

    /** Rails {@code access=}: any value other than Shared deletes every permission row now. */
    private void assignAccess(CrmEntity entity, String assetType, String access) {
        if (!Access.SHARED.railsValue().equals(access)) {
            deletePermissionsImmediate(assetType, entity.getId());
        }
        entity.setAccess(access);
    }

    /** Rails {@code user_ids=}/{@code group_ids=} against the entity's in-memory access. */
    private void assignIds(CrmEntity entity, String assetType, JsonNode node, boolean users,
        PendingPermissions pending) {
        if (!Access.SHARED.railsValue().equals(entity.getAccess())) {
            deletePermissionsImmediate(assetType, entity.getId());
            return;
        }
        List<Long> wanted = normalizeIds(listValue(node));
        String column = users ? "user_id" : "group_id";
        List<Long> current = new ArrayList<>();
        List<Long> stale = new ArrayList<>();
        if (entity.getId() != null) {
            jdbcTemplate.query(
                "SELECT id, " + column + " FROM permissions WHERE asset_type = ? AND asset_id = ? "
                    + "AND " + column + " IS NOT NULL",
                rs -> {
                    long id = rs.getLong(1);
                    long ref = rs.getLong(2);
                    current.add(ref);
                    if (!wanted.contains(ref)) {
                        stale.add(id);
                    }
                }, assetType, entity.getId().intValue());
        }
        if (!stale.isEmpty()) {
            requiresNew.executeWithoutResult(status -> stale.forEach(rowId ->
                jdbcTemplate.update("DELETE FROM permissions WHERE id = ?", rowId)));
        }
        for (Long id : wanted) {
            if (!current.contains(id) && !(users ? pending.users : pending.groups).contains(id)) {
                (users ? pending.users : pending.groups).add(id);
            }
        }
    }

    /** Insert the built permission rows after a successful owner save (Rails autosave semantics). */
    private void flushPendingPermissions(String assetType, Long assetId, PendingPermissions pending) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        pending.users.forEach(userId -> insertPermission(assetType, assetId, userId, null, now));
        pending.groups.forEach(groupId -> insertPermission(assetType, assetId, null, groupId, now));
    }

    private void insertPermission(String assetType, Long assetId, Long userId, Long groupId,
        Instant now) {
        jdbcTemplate.update(
            "INSERT INTO permissions (user_id, group_id, asset_type, asset_id, created_at, "
                + "updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            userId, groupId, assetType, assetId.intValue(),
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    }

    static List<Long> normalizeIds(List<Object> values) {
        java.util.Set<Long> ids = new LinkedHashSet<>();
        for (Object value : values) {
            if (value == null || (value instanceof String text && text.isBlank())) {
                continue;
            }
            if (value instanceof Number number) {
                ids.add(number.longValue());
            } else {
                java.util.regex.Matcher matcher =
                    RUBY_INTEGER.matcher(value.toString().replace("_", ""));
                ids.add(matcher.find() ? Long.parseLong(matcher.group(1)) : 0L);
            }
        }
        return List.copyOf(ids);
    }

    /** {@code users_for_shared_access}: Shared access with no permission rows → share_<model>. */
    private boolean sharedAccessValid(CrmEntity entity, String assetType, long pendingCount) {
        if (!Access.SHARED.railsValue().equals(entity.getAccess())) {
            return true;
        }
        if (pendingCount > 0) {
            return true;
        }
        if (entity.getId() == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM permissions WHERE asset_type = ? AND asset_id = ?",
            Integer.class, assetType, entity.getId().intValue());
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------ tags

    /** {@code tag_list=} on save: replace 'tags'-context taggings (tagger NULL), counter-cache. */
    private void applyTagList(CrmEntity entity, String assetType, JsonNode node) {
        List<String> wanted = new ArrayList<>();
        for (Object raw : listValue(node)) {
            String name = String.valueOf(raw).trim();
            if (!name.isEmpty() && !wanted.contains(name)) {
                wanted.add(name);
            }
        }
        List<Tagging> current = taggingRepository
            .findByTaggableTypeAndTaggableId(assetType, entity.getId().intValue()).stream()
            .filter(tagging -> "tags".equals(tagging.getContext()))
            .toList();
        List<String> have = current.stream()
            .map(tagging -> tagging.getTag().getName()).toList();
        for (Tagging tagging : current) {
            if (!wanted.contains(tagging.getTag().getName())) {
                taggingRepository.delete(tagging);
                touchTaggingsCount(tagging.getTag().getId(), -1);
            }
        }
        for (String name : wanted) {
            if (!have.contains(name)) {
                Tag tag = findOrCreateTag(name);
                Tagging tagging = new Tagging();
                tagging.setTag(tag);
                tagging.setTaggableType(assetType);
                tagging.setTaggableId(entity.getId().intValue());
                tagging.setTaggerType(null);
                tagging.setTaggerId(null);
                tagging.setContext("tags");
                tagging.setCreatedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
                taggingRepository.save(tagging);
                touchTaggingsCount(tag.getId(), 1);
            }
        }
        taggingRepository.flush();
    }

    private Tag findOrCreateTag(String name) {
        return tagRepository.findByName(name).orElseGet(() -> {
            Tag tag = new Tag();
            tag.setName(name);
            tag.setTaggingsCount(0);
            return tagRepository.save(tag);
        });
    }

    private void touchTaggingsCount(Long tagId, int delta) {
        jdbcTemplate.update(
            "UPDATE tags SET taggings_count = COALESCE(taggings_count, 0) + ? WHERE id = ?",
            delta, tagId.intValue());
    }

    // ------------------------------------------------------------------ settings

    private String setting(String name) {
        return settingRepository.findByName(name).map(Setting::getValue).orElse(null);
    }

    private boolean settingFlag(String name, boolean yamlDefault) {
        String value = setting(name);
        if (value == null) {
            return yamlDefault;
        }
        String trimmed = value.replaceAll("^---\\s*", "").trim();
        return "true".equalsIgnoreCase(trimmed);
    }

    /**
     * {@code Setting.unroll(:key)} → list of value strings; YAML scalar lists of {@code :symbol}
     * entries fall back to the built-in defaults when the row is absent.
     */
    private List<String> unroll(String name, List<String> yamlDefault) {
        String value = setting(name);
        if (value == null) {
            return yamlDefault;
        }
        List<String> items = new ArrayList<>();
        for (String line : value.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                items.add(trimmed.substring(2).replaceFirst("^:", "").trim());
            }
        }
        return items.isEmpty() ? yamlDefault : items;
    }

    private static final List<String> ACCOUNT_CATEGORY =
        List.of("affiliate", "competitor", "customer", "partner", "reseller", "vendor");
    private static final List<String> CAMPAIGN_STATUS =
        List.of("planned", "started", "completed", "on_hold", "called_off");
    private static final List<String> LEAD_STATUS =
        List.of("new", "contacted", "converted", "rejected");
    private static final List<String> OPPORTUNITY_STAGE = List.of("prospecting", "analysis",
        "presentation", "proposal", "negotiation", "final_review", "won", "lost");

    // ------------------------------------------------------------------ shared write plumbing

    /**
     * Apply a wrapped entity param map in key order: {@code access}/{@code user_ids}/
     * {@code group_ids} go through the permission writers; {@code tag_list}/{@code cf_*} are
     * collected for post-save handling; every other key is a column assignment.
     */
    private void assignInOrder(CrmEntity entity, String assetType, Map<String, JsonNode> params,
        Map<String, Consumer<JsonNode>> columns, PendingPermissions pending,
        List<String> assignedOrder) {
        for (Map.Entry<String, JsonNode> entry : params.entrySet()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            switch (key) {
                case "access" -> assignAccess(entity, assetType, str(value));
                case "user_ids" -> assignIds(entity, assetType, value, true, pending);
                case "group_ids" -> assignIds(entity, assetType, value, false, pending);
                case "tag_list", "custom_fields" -> {
                    assignedOrder.add(key); // handled after save / read-only
                }
                default -> {
                    Consumer<JsonNode> setter = columns.get(key);
                    if (setter != null) {
                        setter.accept(value);
                        assignedOrder.add(key);
                    }
                    // Unknown keys: permit! would raise on non-columns; contract sends real ones.
                }
            }
        }
    }

    private Map<String, Object> cfInput(Map<String, JsonNode> params) {
        Map<String, Object> input = new LinkedHashMap<>();
        params.forEach((key, node) -> {
            if (key.startsWith("cf_")) {
                input.put(key, node == null || node.isNull() ? null
                    : node.isNumber() ? node.numberValue()
                    : node.isBoolean() ? node.asBoolean() : node.asText());
            }
        });
        return input;
    }

    private void commentBody(AuthenticatedUser user, String railsName, Long id, JsonNode body) {
        String text = str(body);
        if (text == null || text.isBlank()) {
            return;
        }
        Map<String, JsonNode> params = new LinkedHashMap<>();
        params.put("commentable_type",
            com.fasterxml.jackson.databind.node.TextNode.valueOf(railsName));
        params.put("commentable_id",
            com.fasterxml.jackson.databind.node.IntNode.valueOf(id.intValue()));
        params.put("comment", com.fasterxml.jackson.databind.node.TextNode.valueOf(text));
        commentWriteService.create(user, RailsParams.of(params));
    }

    private void counter(String table, String column, Number id, int delta) {
        if (id != null) {
            jdbcTemplate.update("UPDATE " + table + " SET " + column + " = COALESCE(" + column
                + ", 0) + ? WHERE id = ?", delta, id.intValue());
        }
    }

    // ------------------------------------------------------------------ validations

    private void lengthCheck(RailsErrors errors, String model, String attribute, String value,
        int max) {
        if (value != null && value.length() > max) {
            errors.add(attribute, messages.generateMessage(model, attribute, "too_long",
                Map.of("count", max)));
        }
    }

    private void presence(RailsErrors errors, String model, String attribute, String value,
        String key) {
        if (value == null || value.isBlank()) {
            errors.add(messages, model, attribute, key);
        }
    }

    private void inclusion(RailsErrors errors, String model, String attribute, String value,
        List<String> allowed) {
        if (value != null && !value.isBlank() && !allowed.contains(value)) {
            errors.add(messages, model, attribute, "inclusion");
        }
    }

    private void sharedAccessCheck(RailsErrors errors, String model, CrmEntity entity,
        String assetType, PendingPermissions pending) {
        long pendingCount = pending.users.size() + pending.groups.size();
        if (!sharedAccessValid(entity, assetType, pendingCount)) {
            errors.add(messages, model, "access", "share_" + model);
        }
    }

    // ------------------------------------------------------------------ accounts

    @Transactional
    public ObjectNode createAccount(AuthenticatedUser user, JsonNode request) {
        Map<String, JsonNode> params = toMap(field(request, "account"));
        Account account = new Account();
        cancanDefaults(account, user, params);
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        assignInOrder(account, "Account", params, accountColumns(account), pending, order);
        validateAccount(account, pending);
        account = accountRepository.saveAndFlush(account);
        customFieldWriteService.write(account, cfInput(params));
        accountRepository.saveAndFlush(account);
        flushPendingPermissions("Account", account.getId(), pending);
        applyTagListIfProvided(account, "Account", params);
        versionRecorder.recordCreate(user, account,
            rowAttributes(Account.class, account.getId()), ACCOUNT_DEFAULTS);
        commentBody(user, "Account", account.getId(), field(request, "comment_body"));
        return jsonWriter.writeOne(railsResources.account, account.getId());
    }

    @Transactional
    public void updateAccount(AuthenticatedUser user, long id, JsonNode request) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Account " + id));
        Map<String, JsonNode> params = toMap(field(request, "account"));
        Map<String, Object> before = rowAttributes(Account.class, id);
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        // Rails sets access first (permission deletes commit immediately), then update(params).
        JsonNode access = params.get("access");
        if (access != null) {
            assignAccess(account, "Account", str(access));
        }
        assignInOrder(account, "Account", params, accountColumns(account), pending, order);
        validateAccount(account, pending);
        account = accountRepository.saveAndFlush(account);
        customFieldWriteService.write(account, cfInput(params));
        accountRepository.saveAndFlush(account);
        flushPendingPermissions("Account", account.getId(), pending);
        applyTagListIfProvided(account, "Account", params);
        // Rails' post-save attributes order materializes written keys first: the changed param
        // assigns, then save-time writes — deleted_at/id sync and the nullify_blank_category
        // before_save — verified live against PUT /accounts/:id.
        versionRecorder.recordUpdate(user, account, before,
            rowAttributes(Account.class, id), order, List.of("deleted_at", "id", "category"));
    }

    private void cancanDefaults(CrmEntity entity, AuthenticatedUser user,
        Map<String, JsonNode> params) {
        // CanCan attributes_for fills only absent keys.
        if (!params.containsKey("access")) {
            entity.setAccess(Access.PUBLIC.railsValue());
        }
        if (!params.containsKey("user_id")) {
            entity.setUser(userRepository.findById(user.id()).orElse(null));
        }
        if (!params.containsKey("assigned_to")) {
            entity.setAssignedTo(userRepository.findById(user.id()).orElse(null));
        }
    }

    private Map<String, Consumer<JsonNode>> accountColumns(Account account) {
        Map<String, Consumer<JsonNode>> columns = new LinkedHashMap<>();
        columns.put("user_id", v -> account.setUser(userRef(integer(v))));
        columns.put("assigned_to", v -> account.setAssignedTo(userRef(integer(v))));
        columns.put("name", v -> account.setName(str(v)));
        columns.put("website", v -> account.setWebsite(str(v)));
        columns.put("toll_free_phone", v -> account.setTollFreePhone(str(v)));
        columns.put("phone", v -> account.setPhone(str(v)));
        columns.put("fax", v -> account.setFax(str(v)));
        columns.put("deleted_at", v -> account.setDeletedAt(RailsParams.asInstant(v)));
        columns.put("email", v -> account.setEmail(str(v)));
        columns.put("background_info", v -> account.setBackgroundInfo(str(v)));
        columns.put("rating", v -> account.setRating(integer(v)));
        columns.put("category", v -> account.setCategory(str(v)));
        columns.put("contacts_count", v -> account.setContactsCount(integer(v)));
        columns.put("opportunities_count", v -> account.setOpportunitiesCount(integer(v)));
        columns.put("wikidata_id", v -> account.setWikidataId(str(v)));
        columns.put("latitude", v -> account.setLatitude(decimal(v)));
        columns.put("longitude", v -> account.setLongitude(decimal(v)));
        columns.put("blog", v -> account.setBlog(str(v)));
        columns.put("linkedin", v -> account.setLinkedin(str(v)));
        columns.put("facebook", v -> account.setFacebook(str(v)));
        columns.put("twitter", v -> account.setTwitter(str(v)));
        columns.put("bluesky", v -> account.setBluesky(str(v)));
        columns.put("instagram", v -> account.setInstagram(str(v)));
        columns.put("mastodon", v -> account.setMastodon(str(v)));
        return columns;
    }

    private void validateAccount(Account account, PendingPermissions pending) {
        RailsErrors errors = new RailsErrors();
        presence(errors, "account", "name", account.getName(), "missing_account_name");
        if (settingFlag("require_unique_account_names", true)
            && account.getName() != null && !account.getName().isBlank()) {
            Integer dupes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM accounts WHERE name = ? AND deleted_at IS NOT DISTINCT FROM ?"
                    + " AND id <> COALESCE(?, -1)",
                Integer.class, account.getName(), null, account.getId());
            if (dupes != null && dupes > 0) {
                errors.add(messages, "account", "name", "taken");
            }
        }
        if (account.getRating() != null && (account.getRating() < 0 || account.getRating() > 5)) {
            errors.add(messages, "account", "rating", "inclusion");
        }
        inclusion(errors, "account", "category", account.getCategory(),
            unroll("account_category", ACCOUNT_CATEGORY));
        numericRange(errors, "account", "latitude", account.getLatitude(), -90, 90);
        numericRange(errors, "account", "longitude", account.getLongitude(), -180, 180);
        lengthCheck(errors, "account", "name", account.getName(), 64);
        lengthCheck(errors, "account", "website", account.getWebsite(), 64);
        lengthCheck(errors, "account", "toll_free_phone", account.getTollFreePhone(), 32);
        lengthCheck(errors, "account", "phone", account.getPhone(), 32);
        lengthCheck(errors, "account", "fax", account.getFax(), 32);
        lengthCheck(errors, "account", "email", account.getEmail(), 64);
        lengthCheck(errors, "account", "background_info", account.getBackgroundInfo(), 255);
        lengthCheck(errors, "account", "category", account.getCategory(), 32);
        sharedAccessCheck(errors, "account", account, "Account", pending);
        // before_save :nullify_blank_category — only on a save that survives validation.
        if (errors.isEmpty() && account.getCategory() != null && account.getCategory().isBlank()) {
            account.setCategory(null);
        }
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    private void numericRange(RailsErrors errors, String model, String attribute, BigDecimal value,
        double min, double max) {
        if (value == null) {
            return;
        }
        if (value.compareTo(BigDecimal.valueOf(min)) < 0) {
            errors.add(attribute, messages.generateMessage(model, attribute,
                "greater_than_or_equal_to", Map.of("count", (int) min)));
        } else if (value.compareTo(BigDecimal.valueOf(max)) > 0) {
            errors.add(attribute, messages.generateMessage(model, attribute,
                "less_than_or_equal_to", Map.of("count", (int) max)));
        }
    }

    // ------------------------------------------------------------------ campaigns

    @Transactional
    public ObjectNode createCampaign(AuthenticatedUser user, JsonNode request) {
        Map<String, JsonNode> params = toMap(field(request, "campaign"));
        Campaign campaign = new Campaign();
        cancanDefaults(campaign, user, params);
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        assignInOrder(campaign, "Campaign", params, campaignColumns(campaign), pending, order);
        validateCampaign(campaign, pending);
        campaign = campaignRepository.saveAndFlush(campaign);
        customFieldWriteService.write(campaign, cfInput(params));
        campaignRepository.saveAndFlush(campaign);
        flushPendingPermissions("Campaign", campaign.getId(), pending);
        applyTagListIfProvided(campaign, "Campaign", params);
        versionRecorder.recordCreate(user, campaign,
            rowAttributes(Campaign.class, campaign.getId()), CAMPAIGN_DEFAULTS);
        commentBody(user, "Campaign", campaign.getId(), field(request, "comment_body"));
        return jsonWriter.writeOne(railsResources.campaign, campaign.getId());
    }

    @Transactional
    public void updateCampaign(AuthenticatedUser user, long id, JsonNode request) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Campaign " + id));
        Map<String, JsonNode> params = toMap(field(request, "campaign"));
        Map<String, Object> before = rowAttributes(Campaign.class, id);
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        JsonNode access = params.get("access");
        if (access != null) {
            assignAccess(campaign, "Campaign", str(access));
        }
        assignInOrder(campaign, "Campaign", params, campaignColumns(campaign), pending, order);
        validateCampaign(campaign, pending);
        campaign = campaignRepository.saveAndFlush(campaign);
        customFieldWriteService.write(campaign, cfInput(params));
        campaignRepository.saveAndFlush(campaign);
        flushPendingPermissions("Campaign", campaign.getId(), pending);
        applyTagListIfProvided(campaign, "Campaign", params);
        versionRecorder.recordUpdate(user, campaign, before,
            rowAttributes(Campaign.class, id), order);
    }

    private Map<String, Consumer<JsonNode>> campaignColumns(Campaign campaign) {
        Map<String, Consumer<JsonNode>> columns = new LinkedHashMap<>();
        columns.put("user_id", v -> campaign.setUser(userRef(integer(v))));
        columns.put("assigned_to", v -> campaign.setAssignedTo(userRef(integer(v))));
        columns.put("name", v -> campaign.setName(str(v)));
        columns.put("status", v -> campaign.setStatus(str(v)));
        columns.put("budget", v -> campaign.setBudget(decimal(v)));
        columns.put("target_leads", v -> campaign.setTargetLeads(integer(v)));
        columns.put("target_conversion", v -> campaign.setTargetConversion(doubleOf(v)));
        columns.put("target_revenue", v -> campaign.setTargetRevenue(decimal(v)));
        columns.put("leads_count", v -> campaign.setLeadsCount(integer(v)));
        columns.put("opportunities_count", v -> campaign.setOpportunitiesCount(integer(v)));
        columns.put("revenue", v -> campaign.setRevenue(decimal(v)));
        columns.put("starts_on", v -> campaign.setStartsOn(date(v)));
        columns.put("ends_on", v -> campaign.setEndsOn(date(v)));
        columns.put("objectives", v -> campaign.setObjectives(str(v)));
        columns.put("deleted_at", v -> campaign.setDeletedAt(RailsParams.asInstant(v)));
        columns.put("background_info", v -> campaign.setBackgroundInfo(str(v)));
        return columns;
    }

    private static Double doubleOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        try {
            return Double.valueOf(node.asText().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void validateCampaign(Campaign campaign, PendingPermissions pending) {
        RailsErrors errors = new RailsErrors();
        presence(errors, "campaign", "name", campaign.getName(), "missing_campaign_name");
        if (campaign.getName() != null && !campaign.getName().isBlank()) {
            Integer dupes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM campaigns WHERE name = ? AND user_id IS NOT DISTINCT FROM ?"
                    + " AND deleted_at IS NOT DISTINCT FROM ? AND id <> COALESCE(?, -1)",
                Integer.class, campaign.getName(),
                campaign.getUser() == null ? null : campaign.getUser().getId(),
                null, campaign.getId());
            if (dupes != null && dupes > 0) {
                errors.add(messages, "campaign", "name", "taken");
            }
        }
        if (campaign.getStartsOn() != null && campaign.getEndsOn() != null
            && campaign.getStartsOn().isAfter(campaign.getEndsOn())) {
            errors.add(messages, "campaign", "ends_on", "dates_not_in_sequence");
        }
        sharedAccessCheck(errors, "campaign", campaign, "Campaign", pending);
        inclusion(errors, "campaign", "status", campaign.getStatus(),
            unroll("campaign_status", CAMPAIGN_STATUS));
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    // ------------------------------------------------------------------ contacts

    @Transactional
    public ObjectNode createContact(AuthenticatedUser user, JsonNode request) {
        Map<String, JsonNode> params = toMap(field(request, "contact"));
        Contact contact = new Contact();
        cancanDefaults(contact, user, params);
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        // save_with_account_and_permissions: account first, then save, then opportunity link.
        Account account = saveAccountFor(user, contact, field(request, "account"));
        assignInOrder(contact, "Contact", params, contactColumns(contact), pending, order);
        validateContact(contact, pending);
        contact = contactRepository.saveAndFlush(contact);
        setContactAccount(user, contact, account);
        customFieldWriteService.write(contact, cfInput(params));
        contactRepository.saveAndFlush(contact);
        flushPendingPermissions("Contact", contact.getId(), pending);
        applyTagListIfProvided(contact, "Contact", params);
        linkContactOpportunity(contact, field(request, "opportunity"));
        versionRecorder.recordCreate(user, contact,
            rowAttributes(Contact.class, contact.getId()), CONTACT_DEFAULTS);
        commentBody(user, "Contact", contact.getId(), field(request, "comment_body"));
        return jsonWriter.writeOne(railsResources.contact, contact.getId());
    }

    @Transactional
    public void updateContact(AuthenticatedUser user, long id, JsonNode request) {
        Contact contact = contactRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Contact " + id));
        Map<String, JsonNode> params = toMap(field(request, "contact"));
        Map<String, Object> before = rowAttributes(Contact.class, id);
        // save_account runs before access/attributes — no account key unlinks the account.
        Account account = saveAccountFor(user, contact, field(request, "account"));
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        JsonNode access = params.get("access");
        if (access != null) {
            assignAccess(contact, "Contact", str(access));
        }
        assignInOrder(contact, "Contact", params, contactColumns(contact), pending, order);
        validateContact(contact, pending);
        contact = contactRepository.saveAndFlush(contact);
        setContactAccount(user, contact, account);
        customFieldWriteService.write(contact, cfInput(params));
        contactRepository.saveAndFlush(contact);
        flushPendingPermissions("Contact", contact.getId(), pending);
        applyTagListIfProvided(contact, "Contact", params);
        versionRecorder.recordUpdate(user, contact, before,
            rowAttributes(Contact.class, id), order);
    }

    private Map<String, Consumer<JsonNode>> contactColumns(Contact contact) {
        Map<String, Consumer<JsonNode>> columns = new LinkedHashMap<>();
        columns.put("user_id", v -> contact.setUser(userRef(integer(v))));
        columns.put("lead_id", v -> contact.setLead(leadRef(integer(v))));
        columns.put("assigned_to", v -> contact.setAssignedTo(userRef(integer(v))));
        columns.put("reports_to", v -> contact.setReportingUser(userRef(integer(v))));
        columns.put("first_name", v -> contact.setFirstName(str(v)));
        columns.put("last_name", v -> contact.setLastName(str(v)));
        columns.put("title", v -> contact.setTitle(str(v)));
        columns.put("department", v -> contact.setDepartment(str(v)));
        columns.put("source", v -> contact.setSource(str(v)));
        columns.put("email", v -> contact.setEmail(str(v)));
        columns.put("alt_email", v -> contact.setAltEmail(str(v)));
        columns.put("phone", v -> contact.setPhone(str(v)));
        columns.put("mobile", v -> contact.setMobile(str(v)));
        columns.put("fax", v -> contact.setFax(str(v)));
        columns.put("blog", v -> contact.setBlog(str(v)));
        columns.put("linkedin", v -> contact.setLinkedin(str(v)));
        columns.put("facebook", v -> contact.setFacebook(str(v)));
        columns.put("twitter", v -> contact.setTwitter(str(v)));
        columns.put("born_on", v -> contact.setBornOn(date(v)));
        columns.put("do_not_call", v -> contact.setDoNotCall(RailsParams.asBoolean(v)));
        columns.put("deleted_at", v -> contact.setDeletedAt(RailsParams.asInstant(v)));
        columns.put("background_info", v -> contact.setBackgroundInfo(str(v)));
        columns.put("zoom", v -> contact.setZoom(str(v)));
        columns.put("teams", v -> contact.setTeams(str(v)));
        columns.put("signal", v -> contact.setSignal(str(v)));
        columns.put("instagram", v -> contact.setInstagram(str(v)));
        columns.put("mastodon", v -> contact.setMastodon(str(v)));
        columns.put("bluesky", v -> contact.setBluesky(str(v)));
        return columns;
    }

    private Lead leadRef(Integer id) {
        return id == null ? null : leadRepository.findById(id.longValue()).orElse(null);
    }

    private void validateContact(Contact contact, PendingPermissions pending) {
        RailsErrors errors = new RailsErrors();
        if (settingFlag("require_first_names", true)) {
            presence(errors, "contact", "first_name", contact.getFirstName(),
                "missing_first_name");
        }
        if (settingFlag("require_last_names", true)) {
            presence(errors, "contact", "last_name", contact.getLastName(), "missing_last_name");
        }
        if (contact.getUser() == null) {
            errors.add(messages, "contact", "user", "required");
        }
        sharedAccessCheck(errors, "contact", contact, "Contact", pending);
        lengthCheck(errors, "contact", "first_name", contact.getFirstName(), 64);
        lengthCheck(errors, "contact", "last_name", contact.getLastName(), 64);
        lengthCheck(errors, "contact", "title", contact.getTitle(), 64);
        lengthCheck(errors, "contact", "department", contact.getDepartment(), 64);
        lengthCheck(errors, "contact", "source", contact.getSource(), 32);
        lengthCheck(errors, "contact", "email", contact.getEmail(), 64);
        lengthCheck(errors, "contact", "alt_email", contact.getAltEmail(), 64);
        lengthCheck(errors, "contact", "phone", contact.getPhone(), 32);
        lengthCheck(errors, "contact", "mobile", contact.getMobile(), 32);
        lengthCheck(errors, "contact", "fax", contact.getFax(), 32);
        lengthCheck(errors, "contact", "blog", contact.getBlog(), 128);
        lengthCheck(errors, "contact", "linkedin", contact.getLinkedin(), 128);
        lengthCheck(errors, "contact", "facebook", contact.getFacebook(), 128);
        lengthCheck(errors, "contact", "twitter", contact.getTwitter(), 128);
        lengthCheck(errors, "contact", "background_info", contact.getBackgroundInfo(), 255);
        lengthCheck(errors, "contact", "zoom", contact.getZoom(), 128);
        lengthCheck(errors, "contact", "teams", contact.getTeams(), 128);
        lengthCheck(errors, "contact", "signal", contact.getSignal(), 128);
        lengthCheck(errors, "contact", "instagram", contact.getInstagram(), 128);
        lengthCheck(errors, "contact", "mastodon", contact.getMastodon(), 128);
        lengthCheck(errors, "contact", "bluesky", contact.getBluesky(), 128);
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    /**
     * {@code Contact#save_account}: account param with a non-blank id or name →
     * {@code Account.create_or_select_for}; anything else (including an absent key) unlinks the
     * account — the join row is destroyed (AccountContact destroy version + counter decrement).
     */
    private Account saveAccountFor(AuthenticatedUser user, CrmEntity model, JsonNode accountNode) {
        if (accountNode == null || !accountNode.isObject()) {
            return null;
        }
        Map<String, JsonNode> params = toMap(accountNode);
        boolean valid = (params.containsKey("id") && integer(params.get("id")) != null)
            || (params.containsKey("name") && str(params.get("name")) != null
                && !str(params.get("name")).isBlank());
        return valid ? createOrSelectAccount(user, model, params) : null;
    }

    /**
     * {@code Account.create_or_select_for}: explicit id → {@code Account.find} (404, unscoped);
     * non-blank name → unscoped {@code find_by(name:)}; otherwise {@code Account.new(params)} with
     * {@code user = model.user} and save (or {@code save_with_model_permissions} when the chosen
     * access is "Lead").
     */
    private Account createOrSelectAccount(AuthenticatedUser user, CrmEntity model, Map<String, JsonNode> params) {
        Integer id = params.containsKey("id") ? integer(params.get("id")) : null;
        if (id != null) {
            return accountRepository.findById(id.longValue())
                .orElseThrow(() -> new EntityNotFoundException("Account " + id));
        }
        String name = str(params.get("name"));
        if (name != null && !name.isBlank()) {
            List<Account> found = accountRepository.findAll().stream()
                .filter(a -> name.equals(a.getName())).toList();
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        Account account = new Account();
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        assignInOrder(account, "Account", params, accountColumns(account), pending, order);
        if (model != null && model.getUser() != null && account.getUser() == null) {
            account.setUser(model.getUser());
        }
        if ("Lead".equals(account.getAccess()) && model != null) {
            saveWithModelPermissions(user, account, "Account", model, pending);
        } else {
            // Rails account.save returning false leaves errors on the model without raising.
            try {
                validateAccount(account, pending);
            } catch (RailsValidationException invalid) {
                return account;
            }
            account = accountRepository.saveAndFlush(account);
            flushPendingPermissions("Account", account.getId(), pending);
            if (account.getId() != null) {
                versionRecorder.recordCreate(user, account,
                    rowAttributes(Account.class, account.getId()), ACCOUNT_DEFAULTS);
            }
        }
        return account;
    }

    // Nested writes (promote, create_or_select_for) record whodunnit of the request user —
    // PaperTrail.request.whodunnit is set from the controller's current_user.

    /**
     * {@code save_with_model_permissions}: copy the model's access + permission rows onto this
     * entity, then save.
     */
    private void saveWithModelPermissions(AuthenticatedUser user, CrmEntity entity,
        String assetType, CrmEntity model, PendingPermissions pending) {
        entity.setAccess(model.getAccess());
        String modelType = railsNameOf(model);
        if (model.getId() != null) {
            jdbcTemplate.query(
                "SELECT user_id, group_id FROM permissions WHERE asset_type = ? AND asset_id = ?",
                rs -> {
                    Long uid = rs.getObject(1) == null ? null : rs.getLong(1);
                    Long gid = rs.getObject(2) == null ? null : rs.getLong(2);
                    if (uid != null) {
                        pending.users.add(uid);
                    }
                    if (gid != null) {
                        pending.groups.add(gid);
                    }
                }, modelType, model.getId().intValue());
        }
        try {
            validateAny(entity, pending);
        } catch (RailsValidationException invalid) {
            return; // Rails save_with_model_permissions returns false; errors stay on the model
        }
        requiresNew.executeWithoutResult(status -> {
            entityManager.persist(entity);
            entityManager.flush(); // Rails saves are unwrapped — commit independently
        });
        flushPendingPermissions(assetType, entity.getId(), pending);
        versionRecorder.recordCreate(user, entity,
            rowAttributes(entity.getClass(), entity.getId()), Map.of());
    }

    private String railsNameOf(CrmEntity entity) {
        for (RailsModelType type : RailsModelType.values()) {
            if (type.entityClass() == entity.getClass()) {
                return type.railsName();
            }
        }
        throw new IllegalArgumentException("no rails type for " + entity.getClass());
    }

    private void validateAny(CrmEntity entity, PendingPermissions pending) {
        switch (entity) {
            case Account a -> validateAccount(a, pending);
            case Contact c -> validateContact(c, pending);
            case Lead l -> validateLead(l, pending);
            case Opportunity o -> validateOpportunity(o, pending);
            case Campaign c -> validateCampaign(c, pending);
            default -> {
            }
        }
    }

    private void setContactAccount(AuthenticatedUser user, Contact contact, Account account) {
        List<AccountContact> existing = accountContactRepository
            .findByContactId(contact.getId());
        AccountContact link = existing.isEmpty() ? null : existing.get(0);
        if (account == null || account.getId() == null) {
            if (link != null) {
                versionRecorder.recordDestroy(user, link,
                    rowAttributes(AccountContact.class, link.getId()));
                accountContactRepository.delete(link);
                counter("accounts", "contacts_count",
                    link.getAccount().getId(), -1);
            }
        } else if (link == null) {
            AccountContact created = new AccountContact();
            created.setAccount(account);
            created.setContact(contact);
            created = accountContactRepository.saveAndFlush(created);
            counter("accounts", "contacts_count", account.getId(), 1);
            versionRecorder.recordCreate(user, created,
                rowAttributes(AccountContact.class, created.getId()), Map.of());
        } else if (!link.getAccount().getId().equals(account.getId())) {
            versionRecorder.recordDestroy(user, link,
                rowAttributes(AccountContact.class, link.getId()));
            accountContactRepository.delete(link);
            counter("accounts", "contacts_count", link.getAccount().getId(), -1);
            AccountContact created = new AccountContact();
            created.setAccount(account);
            created.setContact(contact);
            created = accountContactRepository.saveAndFlush(created);
            counter("accounts", "contacts_count", account.getId(), 1);
            versionRecorder.recordCreate(user, created,
                rowAttributes(AccountContact.class, created.getId()), Map.of());
        }
    }

    private void linkContactOpportunity(Contact contact, JsonNode opportunityNode) {
        Integer opportunityId = integer(opportunityNode);
        if (opportunityId == null) {
            return;
        }
        Opportunity opportunity = opportunityRepository.findById(opportunityId.longValue())
            .orElseThrow(() -> new EntityNotFoundException("Opportunity " + opportunityId));
        ContactOpportunity link = new ContactOpportunity();
        link.setContact(contact);
        link.setOpportunity(opportunity);
        contactOpportunityRepository.saveAndFlush(link);
        // ContactOpportunity has no paper trail (Rails comments it out).
    }

    // ------------------------------------------------------------------ leads

    @Transactional
    public ObjectNode createLead(AuthenticatedUser user, JsonNode request) {
        Map<String, JsonNode> params = toMap(field(request, "lead"));
        Lead lead = new Lead();
        cancanDefaults(lead, user, params);
        // save_with_permissions: campaign lookup first (404), then Campaign-access copy or plain.
        Integer campaignId = integer(field(request, "campaign"));
        Campaign campaign = null;
        if (campaignId != null) {
            campaign = campaignRepository.findById(campaignId.longValue())
                .orElseThrow(() -> new EntityNotFoundException("Campaign " + campaignId));
            lead.setCampaign(campaign);
        }
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        if ("Campaign".equals(str(params.get("access"))) && campaign != null) {
            Map<String, JsonNode> copy = new LinkedHashMap<>(params);
            copy.remove("access");
            assignInOrder(lead, "Lead", copy, leadColumns(lead), pending, order);
            lead.setAccess(campaign.getAccess());
            copyCampaignPermissions(campaign, pending);
        } else {
            assignInOrder(lead, "Lead", params, leadColumns(lead), pending, order);
        }
        validateLead(lead, pending);
        lead = leadRepository.saveAndFlush(lead);
        counter("campaigns", "leads_count", campaignId, 1);
        customFieldWriteService.write(lead, cfInput(params));
        leadRepository.saveAndFlush(lead);
        flushPendingPermissions("Lead", lead.getId(), pending);
        applyTagListIfProvided(lead, "Lead", params);
        versionRecorder.recordCreate(user, lead, rowAttributes(Lead.class, lead.getId()),
            LEAD_DEFAULTS);
        commentBody(user, "Lead", lead.getId(), field(request, "comment_body"));
        return jsonWriter.writeOne(railsResources.lead, lead.getId());
    }

    private void copyCampaignPermissions(Campaign campaign, PendingPermissions pending) {
        jdbcTemplate.query(
            "SELECT user_id, group_id FROM permissions WHERE asset_type = 'Campaign' AND asset_id = ?",
            rs -> {
                Long uid = rs.getObject(1) == null ? null : rs.getLong(1);
                Long gid = rs.getObject(2) == null ? null : rs.getLong(2);
                if (uid != null) {
                    pending.users.add(uid);
                }
                if (gid != null) {
                    pending.groups.add(gid);
                }
            }, campaign.getId().intValue());
    }

    @Transactional
    public void updateLead(AuthenticatedUser user, long id, JsonNode request) {
        Lead lead = leadRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Lead " + id));
        Map<String, JsonNode> params = toMap(field(request, "lead"));
        Map<String, Object> before = rowAttributes(Lead.class, id);
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        JsonNode access = params.get("access");
        if (access != null) {
            assignAccess(lead, "Lead", str(access));
        }
        // update_with_lead_counters: Ruby `campaign_id == attributes[:campaign_id]` compares a
        // type-cast Integer to the raw param — a JSON string is never equal, a JSON number compares.
        JsonNode rawCampaign = params.get("campaign_id");
        Long oldCampaign = lead.getCampaign() == null ? null : lead.getCampaign().getId();
        boolean sameCampaign = rawCampaign == null || rawCampaign.isNull()
            ? oldCampaign == null
            : rawCampaign.isNumber() && oldCampaign != null
                && oldCampaign == rawCampaign.longValue();
        if (!sameCampaign && oldCampaign != null) {
            counter("campaigns", "leads_count", oldCampaign, -1);
        }
        assignInOrder(lead, "Lead", params, leadColumns(lead), pending, order);
        validateLead(lead, pending);
        lead = leadRepository.saveAndFlush(lead);
        Long newCampaign = lead.getCampaign() == null ? null : lead.getCampaign().getId();
        if (!sameCampaign && newCampaign != null) {
            counter("campaigns", "leads_count", newCampaign, 1);
        }
        customFieldWriteService.write(lead, cfInput(params));
        leadRepository.saveAndFlush(lead);
        flushPendingPermissions("Lead", lead.getId(), pending);
        applyTagListIfProvided(lead, "Lead", params);
        versionRecorder.recordUpdate(user, lead, before, rowAttributes(Lead.class, id), order);
    }

    private Map<String, Consumer<JsonNode>> leadColumns(Lead lead) {
        Map<String, Consumer<JsonNode>> columns = new LinkedHashMap<>();
        columns.put("user_id", v -> lead.setUser(userRef(integer(v))));
        columns.put("campaign_id", v -> lead.setCampaign(
            integer(v) == null ? null
                : campaignRepository.findById(integer(v).longValue()).orElse(null)));
        columns.put("assigned_to", v -> lead.setAssignedTo(userRef(integer(v))));
        columns.put("first_name", v -> lead.setFirstName(str(v)));
        columns.put("last_name", v -> lead.setLastName(str(v)));
        columns.put("title", v -> lead.setTitle(str(v)));
        columns.put("company", v -> lead.setCompany(str(v)));
        columns.put("source", v -> lead.setSource(str(v)));
        columns.put("status", v -> lead.setStatus(str(v)));
        columns.put("referred_by", v -> lead.setReferredBy(str(v)));
        columns.put("email", v -> lead.setEmail(str(v)));
        columns.put("alt_email", v -> lead.setAltEmail(str(v)));
        columns.put("phone", v -> lead.setPhone(str(v)));
        columns.put("mobile", v -> lead.setMobile(str(v)));
        columns.put("blog", v -> lead.setBlog(str(v)));
        columns.put("linkedin", v -> lead.setLinkedin(str(v)));
        columns.put("facebook", v -> lead.setFacebook(str(v)));
        columns.put("twitter", v -> lead.setTwitter(str(v)));
        columns.put("rating", v -> lead.setRating(integer(v)));
        columns.put("do_not_call", v -> lead.setDoNotCall(RailsParams.asBoolean(v)));
        columns.put("deleted_at", v -> lead.setDeletedAt(RailsParams.asInstant(v)));
        columns.put("background_info", v -> lead.setBackgroundInfo(str(v)));
        columns.put("zoom", v -> lead.setZoom(str(v)));
        columns.put("teams", v -> lead.setTeams(str(v)));
        columns.put("signal", v -> lead.setSignal(str(v)));
        columns.put("instagram", v -> lead.setInstagram(str(v)));
        columns.put("mastodon", v -> lead.setMastodon(str(v)));
        columns.put("bluesky", v -> lead.setBluesky(str(v)));
        return columns;
    }

    private void validateLead(Lead lead, PendingPermissions pending) {
        RailsErrors errors = new RailsErrors();
        if (settingFlag("require_first_names", true)) {
            presence(errors, "lead", "first_name", lead.getFirstName(), "missing_first_name");
        }
        if (settingFlag("require_last_names", true)) {
            presence(errors, "lead", "last_name", lead.getLastName(), "missing_last_name");
        }
        sharedAccessCheck(errors, "lead", lead, "Lead", pending);
        inclusion(errors, "lead", "status", lead.getStatus(), unroll("lead_status", LEAD_STATUS));
        lengthCheck(errors, "lead", "first_name", lead.getFirstName(), 64);
        lengthCheck(errors, "lead", "last_name", lead.getLastName(), 64);
        lengthCheck(errors, "lead", "title", lead.getTitle(), 64);
        lengthCheck(errors, "lead", "company", lead.getCompany(), 64);
        lengthCheck(errors, "lead", "source", lead.getSource(), 32);
        lengthCheck(errors, "lead", "status", lead.getStatus(), 32);
        lengthCheck(errors, "lead", "referred_by", lead.getReferredBy(), 64);
        lengthCheck(errors, "lead", "email", lead.getEmail(), 64);
        lengthCheck(errors, "lead", "alt_email", lead.getAltEmail(), 64);
        lengthCheck(errors, "lead", "phone", lead.getPhone(), 32);
        lengthCheck(errors, "lead", "mobile", lead.getMobile(), 32);
        lengthCheck(errors, "lead", "blog", lead.getBlog(), 128);
        lengthCheck(errors, "lead", "linkedin", lead.getLinkedin(), 128);
        lengthCheck(errors, "lead", "facebook", lead.getFacebook(), 128);
        lengthCheck(errors, "lead", "twitter", lead.getTwitter(), 128);
        lengthCheck(errors, "lead", "background_info", lead.getBackgroundInfo(), 255);
        lengthCheck(errors, "lead", "zoom", lead.getZoom(), 128);
        lengthCheck(errors, "lead", "teams", lead.getTeams(), 128);
        lengthCheck(errors, "lead", "signal", lead.getSignal(), 128);
        lengthCheck(errors, "lead", "instagram", lead.getInstagram(), 128);
        lengthCheck(errors, "lead", "mastodon", lead.getMastodon(), 128);
        lengthCheck(errors, "lead", "bluesky", lead.getBluesky(), 128);
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    /**
     * {@code Lead#promote}: create-or-select account, create opportunity, create contact — NOT one
     * transaction in Rails (lead.rb:125-134), so each {@code save} commits independently and a
     * partial promote leaves an orphan Account/Opportunity. This method deliberately has no
     * {@code @Transactional}: Spring Data commits each repository call just like Rails' saves.
     * When any errors remain the controller renders {@code errors + errors + errors} which raises
     * NoMethodError on Rails 8 → 500. On success the lead converts via
     * {@code update_attribute(:status, "converted")}.
     */
    public void promoteLead(AuthenticatedUser user, long id, JsonNode request) {
        Lead lead = leadRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Lead " + id));
        Map<String, JsonNode> accountParams = toMap(field(request, "account"));
        Map<String, JsonNode> opportunityParams = toMap(field(request, "opportunity"));

        Account account = createOrSelectAccount(user, lead, accountParams);
        Map<String, List<String>> accountErrors = quietErrors(() ->
            validateAccountQuietly(account));

        // opportunity.save runs only when account.errors is empty and a name was given; a failed
        // save leaves errors on the model without rolling the account back.
        Opportunity opportunity;
        Map<String, List<String>> opportunityErrors;
        if (accountErrors.isEmpty()) {
            try {
                opportunity = createOpportunityFor(user, lead, account, opportunityParams, true);
                try {
                    validateOpportunityQuietly(opportunity, true);
                    opportunityErrors = Map.of();
                } catch (RailsValidationException exception) {
                    opportunityErrors = exception.errors();
                }
            } catch (RailsValidationException exception) {
                opportunityErrors = exception.errors();
                opportunity = new Opportunity(); // unsaved placeholder; contact.save won't run
            }
        } else {
            opportunity = createOpportunityFor(user, lead, account, opportunityParams, false);
            opportunityErrors = Map.of();
        }

        boolean canSave = accountErrors.isEmpty() && opportunityErrors.isEmpty();
        Map<String, List<String>> contactErrors;
        if (canSave) {
            try {
                createContactFor(user, lead, account, opportunity, request, true);
                contactErrors = Map.of();
            } catch (RailsValidationException exception) {
                contactErrors = exception.errors();
            }
        } else {
            createContactFor(user, lead, account, opportunity, request, false);
            contactErrors = Map.of();
        }

        if (accountErrors.isEmpty() && opportunityErrors.isEmpty() && contactErrors.isEmpty()) {
            // update_attribute skips validations; the save bumps updated_at + writes a version.
            Map<String, Object> before = rowAttributes(Lead.class, lead.getId());
            lead.setStatus("converted");
            leadRepository.saveAndFlush(lead);
            versionRecorder.recordUpdate(user, lead, before,
                rowAttributes(Lead.class, lead.getId()), List.of("status"));
        } else {
            // ActiveModel::Errors#+ is undefined on Rails 8 → NoMethodError → 500.
            throw new RailsInternalError(
                "undefined method `+' for an instance of ActiveModel::Errors");
        }
    }

    private Map<String, List<String>> quietErrors(Runnable validation) {
        try {
            validation.run();
            return Map.of();
        } catch (RailsValidationException exception) {
            return exception.errors();
        }
    }

    private void validateAccountQuietly(Account account) {
        if (account.getId() != null) {
            return; // a persisted account has empty errors in Rails
        }
        validateAccount(account, new PendingPermissions());
    }

    private void validateOpportunityQuietly(Opportunity opportunity, boolean accountOk) {
        if (opportunity.getId() != null) {
            return;
        }
        boolean nameGiven = opportunity.getName() != null && !opportunity.getName().isBlank();
        if (!accountOk || !nameGiven) {
            return; // opportunity.save never ran → errors empty in Rails (name? guard skipped save)
        }
        validateOpportunity(opportunity, new PendingPermissions());
    }

    private Opportunity createOpportunityFor(AuthenticatedUser user, Lead lead, Account account,
        Map<String, JsonNode> params, boolean accountOk) {
        Opportunity opportunity = new Opportunity();
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        assignInOrder(opportunity, "Opportunity", params, opportunityColumns(opportunity),
            pending, order);
        if (opportunity.getName() != null && !opportunity.getName().isBlank() && accountOk) {
            if ("Lead".equals(opportunity.getAccess())) {
                saveWithModelPermissions(user, opportunity, "Opportunity", lead, pending);
            } else {
                validateOpportunity(opportunity, pending);
                opportunity = opportunityRepository.saveAndFlush(opportunity);
                flushPendingPermissions("Opportunity", opportunity.getId(), pending);
            }
            if (account != null && account.getId() != null) {
                AccountOpportunity link = new AccountOpportunity();
                link.setAccount(account);
                link.setOpportunity(opportunity);
                link = accountOpportunityRepository.saveAndFlush(link);
                counter("accounts", "opportunities_count", account.getId(), 1);
                // Rails' promote flow writes the join's create version before the opportunity's.
                versionRecorder.recordCreate(user, link,
                    rowAttributes(AccountOpportunity.class, link.getId()), Map.of());
            }
            if (opportunity.getId() != null) {
                versionRecorder.recordCreate(user, opportunity,
                    rowAttributes(Opportunity.class, opportunity.getId()), OPPORTUNITY_DEFAULTS);
            }
        }
        return opportunity;
    }

    private Contact createContactFor(AuthenticatedUser user, Lead lead, Account account, Opportunity opportunity,
        JsonNode request, boolean canSave) {
        Map<String, JsonNode> accountParams = toMap(field(request, "account"));
        Contact contact = new Contact();
        contact.setLead(lead);
        Integer accountUser = integer(accountParams.get("user_id"));
        contact.setUser(accountUser != null ? userRef(accountUser)
            : account != null ? account.getUser() : null);
        contact.setAssignedTo(userRef(integer(accountParams.get("assigned_to"))));
        contact.setAccess(str(field(request, "access")));
        contact.setFirstName(lead.getFirstName());
        contact.setLastName(lead.getLastName());
        contact.setTitle(lead.getTitle());
        contact.setSource(lead.getSource());
        contact.setEmail(lead.getEmail());
        contact.setAltEmail(lead.getAltEmail());
        contact.setPhone(lead.getPhone());
        contact.setMobile(lead.getMobile());
        contact.setBlog(lead.getBlog());
        contact.setLinkedin(lead.getLinkedin());
        contact.setFacebook(lead.getFacebook());
        contact.setTwitter(lead.getTwitter());
        contact.setZoom(lead.getZoom());
        contact.setTeams(lead.getTeams());
        contact.setSignal(lead.getSignal());
        contact.setInstagram(lead.getInstagram());
        contact.setMastodon(lead.getMastodon());
        contact.setBluesky(lead.getBluesky());
        contact.setDoNotCall(lead.getDoNotCall());
        contact.setBackgroundInfo(lead.getBackgroundInfo());
        if (canSave) {
            PendingPermissions pending = new PendingPermissions();
            if ("Lead".equals(contact.getAccess())) {
                saveWithModelPermissions(user, contact, "Contact", lead, pending);
            } else {
                validateContact(contact, pending);
                contact = contactRepository.saveAndFlush(contact);
                flushPendingPermissions("Contact", contact.getId(), pending);
                versionRecorder.recordCreate(user, contact,
                    rowAttributes(Contact.class, contact.getId()), CONTACT_DEFAULTS);
            }
            if (account != null && account.getId() != null) {
                setContactAccount(user, contact, account);
            }
            if (opportunity.getId() != null) {
                ContactOpportunity link = new ContactOpportunity();
                link.setContact(contact);
                link.setOpportunity(opportunity);
                contactOpportunityRepository.saveAndFlush(link);
            }
        }
        // Rails never runs contact.save here → contact.errors stays empty; nothing persists.
        return contact;
    }

    /** {@code Lead#reject}: {@code update_attribute(:status, "rejected")} → 204. */
    @Transactional
    public void rejectLead(AuthenticatedUser user, long id) {
        Lead lead = leadRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Lead " + id));
        Map<String, Object> before = rowAttributes(Lead.class, id);
        lead.setStatus("rejected");
        leadRepository.saveAndFlush(lead);
        versionRecorder.recordUpdate(user, lead, before, rowAttributes(Lead.class, id),
            List.of("status"));
        // LeadObserver#after_update: status → rejected logs a `reject` activity version.
        versionRecorder.recordEvent(user, RailsModelType.LEAD, id, "reject");
    }

    /** {@code GET /leads/:id/convert} renders the lead like show → 200 + lead JSON. */
    @Transactional(readOnly = true)
    public ObjectNode convertLead(long id) {
        if (!leadRepository.existsById(id)) {
            throw new EntityNotFoundException("Lead " + id);
        }
        return jsonWriter.writeOne(railsResources.lead, id);
    }

    // ------------------------------------------------------------------ opportunities

    @Transactional
    public ObjectNode createOpportunity(AuthenticatedUser user, JsonNode request) {
        JsonNode accountNode = field(request, "account");
        if (accountNode == null || !accountNode.isObject()) {
            // params[:account][:id] → NoMethodError on nil → 500.
            throw new RailsInternalError("undefined method `[]' for nil");
        }
        Map<String, JsonNode> accountParams = toMap(accountNode);
        Map<String, JsonNode> params = toMap(field(request, "opportunity"));
        Opportunity opportunity = new Opportunity();
        cancanDefaults(opportunity, user, params);
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        // params[:account].delete(:id) if blank; then create_or_select_for.
        Integer accountId = integer(accountParams.get("id"));
        if (accountId == null) {
            accountParams.remove("id");
        }
        Account account = createOrSelectAccount(user, opportunity, accountParams);
        Integer campaignId = integer(field(request, "campaign"));
        Campaign campaign = null;
        if (campaignId != null) {
            campaign = campaignRepository.findById(campaignId.longValue())
                .orElseThrow(() -> new EntityNotFoundException("Campaign " + campaignId));
        }
        assignInOrder(opportunity, "Opportunity", params, opportunityColumns(opportunity),
            pending, order);
        opportunity.setCampaign(campaign);
        validateOpportunity(opportunity, pending);
        opportunity = opportunityRepository.saveAndFlush(opportunity);
        counter("campaigns", "opportunities_count", campaignId, 1);
        if (account != null && account.getId() != null) {
            AccountOpportunity link = new AccountOpportunity();
            link.setAccount(account);
            link.setOpportunity(opportunity);
            link = accountOpportunityRepository.saveAndFlush(link);
            counter("accounts", "opportunities_count", account.getId(), 1);
            versionRecorder.recordCreate(user, link,
                rowAttributes(AccountOpportunity.class, link.getId()), Map.of());
        }
        customFieldWriteService.write(opportunity, cfInput(params));
        opportunityRepository.saveAndFlush(opportunity);
        flushPendingPermissions("Opportunity", opportunity.getId(), pending);
        applyTagListIfProvided(opportunity, "Opportunity", params);
        Integer contactId = integer(field(request, "contact"));
        if (contactId != null) {
            Contact contact = contactRepository.findById(contactId.longValue())
                .orElseThrow(() -> new EntityNotFoundException("Contact " + contactId));
            ContactOpportunity link = new ContactOpportunity();
            link.setContact(contact);
            link.setOpportunity(opportunity);
            contactOpportunityRepository.saveAndFlush(link);
        }
        versionRecorder.recordCreate(user, opportunity,
            rowAttributes(Opportunity.class, opportunity.getId()), OPPORTUNITY_DEFAULTS);
        commentBody(user, "Opportunity", opportunity.getId(), field(request, "comment_body"));
        return jsonWriter.writeOne(railsResources.opportunity, opportunity.getId());
    }

    @Transactional
    public void updateOpportunity(AuthenticatedUser user, long id, JsonNode request) {
        Opportunity opportunity = opportunityRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Opportunity " + id));
        Map<String, JsonNode> params = toMap(field(request, "opportunity"));
        Map<String, Object> before = rowAttributes(Opportunity.class, id);
        JsonNode accountNode = field(request, "account");
        if (accountNode != null && accountNode.isObject()) {
            Map<String, JsonNode> accountParams = toMap(accountNode);
            boolean unlink = "".equals(str(accountParams.get("id")))
                || "".equals(str(accountParams.get("name")));
            if (unlink) {
                setOpportunityAccount(user, opportunity, null);
            } else {
                setOpportunityAccount(user, opportunity,
                    createOrSelectAccount(user, opportunity, accountParams));
            }
        }
        PendingPermissions pending = new PendingPermissions();
        List<String> order = new ArrayList<>();
        JsonNode access = params.get("access");
        if (access != null) {
            assignAccess(opportunity, "Opportunity", str(access));
        }
        assignInOrder(opportunity, "Opportunity", params, opportunityColumns(opportunity),
            pending, order);
        validateOpportunity(opportunity, pending);
        opportunity = opportunityRepository.saveAndFlush(opportunity);
        customFieldWriteService.write(opportunity, cfInput(params));
        opportunityRepository.saveAndFlush(opportunity);
        flushPendingPermissions("Opportunity", opportunity.getId(), pending);
        applyTagListIfProvided(opportunity, "Opportunity", params);
        versionRecorder.recordUpdate(user, opportunity, before,
            rowAttributes(Opportunity.class, id), order);
        runOpportunityObserver(user, opportunity, id, before, order);
    }

    private void setOpportunityAccount(AuthenticatedUser user, Opportunity opportunity,
        Account account) {
        List<AccountOpportunity> existing = accountOpportunityRepository
            .findByOpportunityId(opportunity.getId());
        AccountOpportunity link = existing.isEmpty() ? null : existing.get(0);
        if (link != null) {
            versionRecorder.recordDestroy(user, link,
                rowAttributes(AccountOpportunity.class, link.getId()));
            accountOpportunityRepository.delete(link);
            counter("accounts", "opportunities_count", link.getAccount().getId(), -1);
        }
        if (account != null && account.getId() != null) {
            AccountOpportunity created = new AccountOpportunity();
            created.setAccount(account);
            created.setOpportunity(opportunity);
            created = accountOpportunityRepository.saveAndFlush(created);
            counter("accounts", "opportunities_count", account.getId(), 1);
            versionRecorder.recordCreate(user, created,
                rowAttributes(AccountOpportunity.class, created.getId()), Map.of());
        }
    }

    private Map<String, Consumer<JsonNode>> opportunityColumns(Opportunity opportunity) {
        Map<String, Consumer<JsonNode>> columns = new LinkedHashMap<>();
        columns.put("user_id", v -> opportunity.setUser(userRef(integer(v))));
        columns.put("campaign_id", v -> opportunity.setCampaign(
            integer(v) == null ? null
                : campaignRepository.findById(integer(v).longValue()).orElse(null)));
        columns.put("assigned_to", v -> opportunity.setAssignedTo(userRef(integer(v))));
        columns.put("name", v -> opportunity.setName(str(v)));
        columns.put("source", v -> opportunity.setSource(str(v)));
        columns.put("stage", v -> opportunity.setStage(str(v)));
        columns.put("probability", v -> opportunity.setProbability(integer(v)));
        columns.put("amount", v -> opportunity.setAmount(decimal(v)));
        columns.put("discount", v -> opportunity.setDiscount(decimal(v)));
        columns.put("closes_on", v -> opportunity.setClosesOn(date(v)));
        columns.put("deleted_at", v -> opportunity.setDeletedAt(RailsParams.asInstant(v)));
        columns.put("background_info", v -> opportunity.setBackgroundInfo(str(v)));
        return columns;
    }

    /**
     * OpportunityObserver#after_update: won → campaign revenue += amount−discount, then
     * {@code update_attribute(:probability, 100)} (a second update version), then a {@code won}
     * activity version; won→other → campaign revenue -= original amount−discount; other→lost →
     * {@code update_attribute(:probability, 0)}.
     */
    private void runOpportunityObserver(AuthenticatedUser user, Opportunity opportunity, long id,
        Map<String, Object> before, List<String> order) {
        String beforeStage = before.get("stage") == null ? null : before.get("stage").toString();
        String afterStage = opportunity.getStage();
        if ("won".equals(beforeStage) == "won".equals(afterStage)) {
            if (!"lost".equals(beforeStage) && "lost".equals(afterStage)) {
                Map<String, Object> mid = rowAttributes(Opportunity.class, id);
                opportunity.setProbability(0);
                opportunityRepository.saveAndFlush(opportunity);
                // The version object's attribute order carries the earlier save's write order.
                versionRecorder.recordUpdate(user, opportunity, mid,
                    rowAttributes(Opportunity.class, id), List.of(), order);
            }
            return;
        }
        if ("won".equals(afterStage)) {
            updateCampaignRevenue(user, opportunity.getCampaign(),
                opportunity.getAmount(), opportunity.getDiscount(), 1);
            Map<String, Object> mid = rowAttributes(Opportunity.class, id);
            opportunity.setProbability(100);
            opportunityRepository.saveAndFlush(opportunity);
            versionRecorder.recordUpdate(user, opportunity, mid,
                rowAttributes(Opportunity.class, id), List.of(), order);
            versionRecorder.recordEvent(user, RailsModelType.OPPORTUNITY, id, "won");
        } else {
            // Unwin: revenue -= (original amount − original discount) on the original campaign.
            Object campaignId = before.get("campaign_id");
            Campaign original = campaignId == null ? null
                : campaignRepository.findById(((Number) campaignId).longValue()).orElse(null);
            updateCampaignRevenue(user, original,
                decimalOf(before.get("amount")), decimalOf(before.get("discount")), -1);
        }
    }

    private static java.math.BigDecimal decimalOf(Object value) {
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return java.math.BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    /** {@code campaign.update_attribute(:revenue, revenue + delta)} → campaign update version. */
    private void updateCampaignRevenue(AuthenticatedUser user, Campaign campaign,
        java.math.BigDecimal amount, java.math.BigDecimal discount, int sign) {
        if (campaign == null) {
            return;
        }
        java.math.BigDecimal base = amount == null ? java.math.BigDecimal.ZERO : amount;
        base = base.subtract(discount == null ? java.math.BigDecimal.ZERO : discount);
        java.math.BigDecimal delta = sign > 0 ? base : base.negate();
        Map<String, Object> campaignBefore = rowAttributes(Campaign.class, campaign.getId());
        java.math.BigDecimal revenue = campaign.getRevenue() == null
            ? java.math.BigDecimal.ZERO : campaign.getRevenue();
        campaign.setRevenue(revenue.add(delta));
        campaignRepository.saveAndFlush(campaign);
        versionRecorder.recordUpdate(user, campaign, campaignBefore,
            rowAttributes(Campaign.class, campaign.getId()), List.of("revenue"));
    }

    private void validateOpportunity(Opportunity opportunity, PendingPermissions pending) {
        RailsErrors errors = new RailsErrors();
        presence(errors, "opportunity", "name", opportunity.getName(), "missing_opportunity_name");
        sharedAccessCheck(errors, "opportunity", opportunity, "Opportunity", pending);
        inclusion(errors, "opportunity", "stage", opportunity.getStage(),
            unroll("opportunity_stage", OPPORTUNITY_STAGE));
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    // ------------------------------------------------------------------ destroy

    @Transactional
    public void destroy(AuthenticatedUser user, long id, String family) {
        switch (family) {
            case "accounts" -> destroyAccount(user, id);
            case "campaigns" -> destroyCampaign(user, id);
            case "contacts" -> destroyContact(user, id);
            case "leads" -> destroyLead(user, id);
            case "opportunities" -> destroyOpportunity(user, id);
            default -> throw new IllegalArgumentException(family);
        }
    }

    private void destroyDependents(AuthenticatedUser user, String railsName, Long id) {
        for (Task task : taskRepository.findByAssetTypeAndAssetId(railsName, id.intValue())) {
            versionRecorder.recordDestroy(user, task, EntityAttributes.of(task));
            taskRepository.delete(task);
        }
        for (Address address : addressRepository
            .findByAddressableTypeAndAddressableId(railsName, id.intValue())) {
            versionRecorder.recordDestroy(user, address,
                rowAttributes(Address.class, address.getId()));
            addressRepository.delete(address);
        }
        for (com.fatfreecrm.domain.Comment comment : commentRepository
            .findByCommentableTypeAndCommentableId(railsName, id.intValue())) {
            versionRecorder.recordDestroy(user, comment, EntityAttributes.of(comment));
            commentRepository.delete(comment);
        }
        for (Tagging tagging : taggingRepository
            .findByTaggableTypeAndTaggableId(railsName, id.intValue())) {
            taggingRepository.delete(tagging);
            touchTaggingsCount(tagging.getTag().getId(), -1);
        }
    }

    private void destroyAccount(AuthenticatedUser user, long id) {
        Account account = accountRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Account " + id));
        // PaperTrail's destroy snapshot precedes the dependent destroys that decrement counters.
        Map<String, Object> attributes = rowAttributes(Account.class, id);
        for (AccountContact link : accountContactRepository.findByAccountId(id)) {
            versionRecorder.recordDestroy(user, link,
                rowAttributes(AccountContact.class, link.getId()));
            accountContactRepository.delete(link);
            counter("accounts", "contacts_count", id, -1);
        }
        for (AccountOpportunity link : accountOpportunityRepository.findByAccountId(id)) {
            versionRecorder.recordDestroy(user, link,
                rowAttributes(AccountOpportunity.class, link.getId()));
            accountOpportunityRepository.delete(link);
            counter("accounts", "opportunities_count", id, -1);
        }
        destroyDependents(user, "Account", id);
        versionRecorder.recordDestroy(user, account, attributes);
        accountRepository.delete(account);
    }

    private void destroyCampaign(AuthenticatedUser user, long id) {
        Campaign campaign = campaignRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Campaign " + id));
        destroyDependents(user, "Campaign", id);
        Map<String, Object> attributes = rowAttributes(Campaign.class, id);
        versionRecorder.recordDestroy(user, campaign, attributes);
        campaignRepository.delete(campaign);
    }

    private void destroyContact(AuthenticatedUser user, long id) {
        Contact contact = contactRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Contact " + id));
        for (AccountContact link : accountContactRepository.findByContactId(id)) {
            versionRecorder.recordDestroy(user, link,
                rowAttributes(AccountContact.class, link.getId()));
            accountContactRepository.delete(link);
            counter("accounts", "contacts_count", link.getAccount().getId(), -1);
        }
        for (ContactOpportunity link : contactOpportunityRepository.findByContactId(id)) {
            contactOpportunityRepository.delete(link); // no paper trail
        }
        destroyDependents(user, "Contact", id);
        Map<String, Object> attributes = rowAttributes(Contact.class, id);
        versionRecorder.recordDestroy(user, contact, attributes);
        contactRepository.delete(contact);
    }

    private void destroyLead(AuthenticatedUser user, long id) {
        Lead lead = leadRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Lead " + id));
        for (Contact contact : contactRepository.findByLeadId(id)) {
            Map<String, Object> before = rowAttributes(Contact.class, contact.getId());
            contact.setLead(null);
            contactRepository.saveAndFlush(contact);
            versionRecorder.recordUpdate(user, contact, before,
                rowAttributes(Contact.class, contact.getId()), List.of("lead_id"));
        }
        destroyDependents(user, "Lead", id);
        Map<String, Object> attributes = rowAttributes(Lead.class, id);
        versionRecorder.recordDestroy(user, lead, attributes);
        leadRepository.delete(lead);
        counter("campaigns", "leads_count",
            lead.getCampaign() == null ? null : lead.getCampaign().getId(), -1);
    }

    private void destroyOpportunity(AuthenticatedUser user, long id) {
        Opportunity opportunity = opportunityRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Opportunity " + id));
        for (AccountOpportunity link : accountOpportunityRepository.findByOpportunityId(id)) {
            versionRecorder.recordDestroy(user, link,
                rowAttributes(AccountOpportunity.class, link.getId()));
            accountOpportunityRepository.delete(link);
            counter("accounts", "opportunities_count", link.getAccount().getId(), -1);
        }
        for (ContactOpportunity link : contactOpportunityRepository.findByOpportunityId(id)) {
            contactOpportunityRepository.delete(link);
        }
        destroyDependents(user, "Opportunity", id);
        Map<String, Object> attributes = rowAttributes(Opportunity.class, id);
        versionRecorder.recordDestroy(user, opportunity, attributes);
        opportunityRepository.delete(opportunity);
        counter("campaigns", "opportunities_count",
            opportunity.getCampaign() == null ? null : opportunity.getCampaign().getId(), -1);
    }

    // ------------------------------------------------------------------ attach / discard / subscribe

    /**
     * {@code EntitiesController#attach}: {@code find_class(assets).find(asset_id)} → 404 when the
     * asset is missing or the class is unknown; {@code entity.attach!} then 204.
     */
    @Transactional
    public void attach(AuthenticatedUser user, String family, long id, String assets, Long assetId) {
        CrmEntity entity = load(family, id);
        Object attachment = findAsset(assets, assetId);
        switch (entity) {
            case Account account -> attachToAccount(user, account, attachment);
            case Campaign campaign -> attachToCampaign(user, campaign, attachment);
            case Contact contact -> attachToContact(user, contact, attachment);
            case Lead lead -> {
                if (attachment instanceof Task task) {
                    attachTask(user, lead, task, "Lead");
                } else {
                    // leads.attach! only knows tasks; Rails would NoMethodError → 500.
                    throw new RailsInternalError("undefined method `"
                        + attachment.getClass().getSimpleName().toLowerCase() + "s'");
                }
            }
            case Opportunity opportunity -> attachToOpportunity(user, opportunity, attachment);
            default -> throw new IllegalArgumentException(family);
        }
    }

    private Object findAsset(String assets, Long assetId) {
        if (assetId == null || assets == null) {
            throw new EntityNotFoundException("asset");
        }
        String modelName = switch (assets) {
            case "contact", "contacts" -> "Contact";
            case "opportunity", "opportunities" -> "Opportunity";
            case "lead", "leads" -> "Lead";
            case "account", "accounts" -> "Account";
            case "campaign", "campaigns" -> "Campaign";
            case "task", "tasks" -> "Task";
            case "email", "emails" -> "Email";
            case "comment", "comments" -> "Comment";
            default -> throw new EntityNotFoundException(assets);
        };
        Object found = entityManager.find(
            RailsModelType.fromRailsName(modelName)
                .orElseThrow(() -> new EntityNotFoundException(assets)).entityClass(), assetId);
        if (found == null) {
            throw new EntityNotFoundException(modelName + " " + assetId);
        }
        return found;
    }

    private void attachTask(AuthenticatedUser user, CrmEntity entity, Task task, String railsName) {
        List<Task> existing = taskRepository.findByAssetTypeAndAssetId(
            railsName, entity.getId().intValue());
        if (existing.stream().anyMatch(t -> t.getId().equals(task.getId()))) {
            return;
        }
        Map<String, Object> before = EntityAttributes.of(task);
        task.setAssetType(railsName);
        task.setAssetId(entity.getId().intValue());
        taskRepository.saveAndFlush(task);
        versionRecorder.recordUpdate(user, task, before, EntityAttributes.of(task),
            List.of("asset_type", "asset_id"));
    }

    private void attachToAccount(AuthenticatedUser user, Account account, Object attachment) {
        switch (attachment) {
            case Contact contact -> {
                if (accountContactRepository.findByAccountId(account.getId()).stream()
                    .anyMatch(l -> l.getContact().getId().equals(contact.getId()))) {
                    return;
                }
                AccountContact link = new AccountContact();
                link.setAccount(account);
                link.setContact(contact);
                link = accountContactRepository.saveAndFlush(link);
                counter("accounts", "contacts_count", account.getId(), 1);
                versionRecorder.recordCreate(user, link,
                    rowAttributes(AccountContact.class, link.getId()), Map.of());
            }
            case Opportunity opportunity -> {
                if (accountOpportunityRepository.findByAccountId(account.getId()).stream()
                    .anyMatch(l -> l.getOpportunity().getId().equals(opportunity.getId()))) {
                    return;
                }
                AccountOpportunity link = new AccountOpportunity();
                link.setAccount(account);
                link.setOpportunity(opportunity);
                link = accountOpportunityRepository.saveAndFlush(link);
                counter("accounts", "opportunities_count", account.getId(), 1);
                versionRecorder.recordCreate(user, link,
                    rowAttributes(AccountOpportunity.class, link.getId()), Map.of());
            }
            case Task task -> attachTask(user, account, task, "Account");
            default -> throw new EntityNotFoundException("attachment");
        }
    }

    private void attachToCampaign(AuthenticatedUser user, Campaign campaign, Object attachment) {
        switch (attachment) {
            case Lead lead -> {
                if (campaign.getId().equals(
                    lead.getCampaign() == null ? null : lead.getCampaign().getId())) {
                    return;
                }
                Map<String, Object> before = rowAttributes(Lead.class, lead.getId());
                lead.setCampaign(campaign);
                leadRepository.saveAndFlush(lead); // update_attribute → save, writes a version
                counter("campaigns", "leads_count", campaign.getId(), 1);
                versionRecorder.recordUpdate(user, lead, before,
                    rowAttributes(Lead.class, lead.getId()), List.of("campaign_id"));
            }
            case Opportunity opportunity -> {
                if (campaign.getId().equals(
                    opportunity.getCampaign() == null ? null : opportunity.getCampaign().getId())) {
                    return;
                }
                Map<String, Object> before = rowAttributes(Opportunity.class, opportunity.getId());
                opportunity.setCampaign(campaign);
                opportunityRepository.saveAndFlush(opportunity);
                counter("campaigns", "opportunities_count", campaign.getId(), 1);
                versionRecorder.recordUpdate(user, opportunity, before,
                    rowAttributes(Opportunity.class, opportunity.getId()), List.of("campaign_id"));
            }
            case Task task -> attachTask(user, campaign, task, "Campaign");
            default -> throw new EntityNotFoundException("attachment");
        }
    }

    private void attachToContact(AuthenticatedUser user, Contact contact, Object attachment) {
        switch (attachment) {
            case Opportunity opportunity -> {
                if (contactOpportunityRepository.findByContactId(contact.getId()).stream()
                    .anyMatch(l -> l.getOpportunity().getId().equals(opportunity.getId()))) {
                    return;
                }
                ContactOpportunity link = new ContactOpportunity();
                link.setContact(contact);
                link.setOpportunity(opportunity);
                contactOpportunityRepository.saveAndFlush(link);
            }
            case Task task -> attachTask(user, contact, task, "Contact");
            default -> throw new EntityNotFoundException("attachment");
        }
    }

    private void attachToOpportunity(AuthenticatedUser user, Opportunity opportunity,
        Object attachment) {
        switch (attachment) {
            case Contact contact -> {
                if (contactOpportunityRepository.findByOpportunityId(opportunity.getId()).stream()
                    .anyMatch(l -> l.getContact().getId().equals(contact.getId()))) {
                    return;
                }
                ContactOpportunity link = new ContactOpportunity();
                link.setContact(contact);
                link.setOpportunity(opportunity);
                contactOpportunityRepository.saveAndFlush(link);
            }
            case Task task -> attachTask(user, opportunity, task, "Opportunity");
            default -> throw new EntityNotFoundException("attachment");
        }
    }

    /**
     * {@code EntitiesController#discard}: POST → 201 + entity JSON. Join rows are deleted with no
     * callbacks (no counter decrement, no destroy version); task discard nils the asset via
     * update_attribute (a Task update version IS written); campaign lead/opp discard decrements the
     * counter then update_attribute(:campaign, nil) writes a version.
     */
    @Transactional
    public ObjectNode discard(AuthenticatedUser user, String family, long id, String attachmentType,
        Long attachmentId) {
        CrmEntity entity = load(family, id);
        Object attachment = findAsset(attachmentType, attachmentId);
        switch (entity) {
            case Account account -> {
                if (attachment instanceof Task task) {
                    detachTask(user, task);
                } else if (attachment instanceof Contact contact) {
                    accountContactRepository.findByAccountId(account.getId()).stream()
                        .filter(l -> l.getContact().getId().equals(contact.getId()))
                        .forEach(accountContactRepository::delete); // delete: no callbacks
                } else if (attachment instanceof Opportunity opportunity) {
                    accountOpportunityRepository.findByAccountId(account.getId()).stream()
                        .filter(l -> l.getOpportunity().getId().equals(opportunity.getId()))
                        .forEach(accountOpportunityRepository::delete);
                }
            }
            case Campaign campaign -> {
                if (attachment instanceof Task task) {
                    detachTask(user, task);
                } else if (attachment instanceof Lead lead) {
                    // discard! decrements the attachment's OWN campaign, not the entity's.
                    counter("campaigns", "leads_count",
                        lead.getCampaign() == null ? null : lead.getCampaign().getId(), -1);
                    Map<String, Object> before = rowAttributes(Lead.class, lead.getId());
                    lead.setCampaign(null);
                    leadRepository.saveAndFlush(lead);
                    versionRecorder.recordUpdate(user, lead, before,
                        rowAttributes(Lead.class, lead.getId()), List.of("campaign_id"));
                } else if (attachment instanceof Opportunity opportunity) {
                    counter("campaigns", "opportunities_count",
                        opportunity.getCampaign() == null ? null : opportunity.getCampaign().getId(),
                        -1);
                    Map<String, Object> before =
                        rowAttributes(Opportunity.class, opportunity.getId());
                    opportunity.setCampaign(null);
                    opportunityRepository.saveAndFlush(opportunity);
                    versionRecorder.recordUpdate(user, opportunity, before,
                        rowAttributes(Opportunity.class, opportunity.getId()),
                        List.of("campaign_id"));
                }
            }
            case Contact contact -> {
                if (attachment instanceof Task task) {
                    detachTask(user, task);
                } else if (attachment instanceof Opportunity opportunity) {
                    contactOpportunityRepository.findByContactId(contact.getId()).stream()
                        .filter(l -> l.getOpportunity().getId().equals(opportunity.getId()))
                        .forEach(contactOpportunityRepository::delete);
                }
            }
            case Lead lead -> {
                if (attachment instanceof Task task) {
                    detachTask(user, task);
                }
            }
            case Opportunity opportunity -> {
                if (attachment instanceof Task task) {
                    detachTask(user, task);
                } else if (attachment instanceof Contact contact) {
                    contactOpportunityRepository.findByOpportunityId(opportunity.getId()).stream()
                        .filter(l -> l.getContact().getId().equals(contact.getId()))
                        .forEach(contactOpportunityRepository::delete);
                }
            }
            default -> throw new IllegalArgumentException(family);
        }
        entityManager.flush();
        return jsonWriter.writeOne(resource(family), id);
    }

    /** {@code update_attribute(:asset, nil)}: skips validations, writes an update version. */
    private void detachTask(AuthenticatedUser user, Task task) {
        Map<String, Object> before = EntityAttributes.of(task);
        task.setAssetType(null);
        task.setAssetId(null);
        taskRepository.saveAndFlush(task);
        versionRecorder.recordUpdate(user, task, before, EntityAttributes.of(task),
            List.of("asset_type", "asset_id"));
    }

    /**
     * {@code EntitiesController#subscribe}: the subscribed_users append saves first (FOR UPDATE),
     * then {@code respond_with(@entity)} hits the nil ivar → UrlGenerationError → 500. The entity
     * save runs validations; when they fail (Shared with no perms) nothing persists but the 500
     * still fires.
     */
    public void subscribe(AuthenticatedUser user, String family, long id) {
        // The subscribed_users save commits before the 500 fires — REQUIRES_NEW so the thrown
        // RailsInternalError can't roll it back (Rails has no wrapping transaction either).
        requiresNew.executeWithoutResult(status -> {
            CrmEntity entity = (CrmEntity) entityManager.find(entityClass(family), id,
                LockModeType.PESSIMISTIC_WRITE);
            if (entity == null) {
                throw new EntityNotFoundException(family + " " + id);
            }
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            List<Long> subscribed = new ArrayList<>(entity.getSubscribedUsers());
            subscribed.add(user.id());
            entity.setSubscribedUsers(subscribed); // reassignment, like subscribed_users +=
            if (entitySaveable(entity, railsNameOf(entity))) {
                entityManager.flush();
            }
        });
        throw new RailsInternalError("Nil location provided. Can't build URI.");
    }

    private boolean entitySaveable(CrmEntity entity, String railsName) {
        RailsErrors errors = new RailsErrors();
        switch (entity) {
            case Account a -> presence(errors, "account", "name", a.getName(),
                "missing_account_name");
            case Campaign c -> presence(errors, "campaign", "name", c.getName(),
                "missing_campaign_name");
            case Contact c -> {
                presence(errors, "contact", "first_name", c.getFirstName(), "missing_first_name");
                presence(errors, "contact", "last_name", c.getLastName(), "missing_last_name");
            }
            case Lead l -> {
                presence(errors, "lead", "first_name", l.getFirstName(), "missing_first_name");
                presence(errors, "lead", "last_name", l.getLastName(), "missing_last_name");
            }
            case Opportunity o -> presence(errors, "opportunity", "name", o.getName(),
                "missing_opportunity_name");
            default -> {
            }
        }
        if (!errors.isEmpty()) {
            return false;
        }
        return sharedAccessValid(entity, railsName, 0);
    }

    /** {@code EntitiesController#unsubscribe}: remove + save, then 201 + entity JSON. */
    @Transactional
    public ObjectNode unsubscribe(AuthenticatedUser user, String family, long id) {
        CrmEntity entity = (CrmEntity) entityManager.find(entityClass(family), id,
            LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) {
            throw new EntityNotFoundException(family + " " + id);
        }
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        List<Long> subscribed = new ArrayList<>(entity.getSubscribedUsers());
        subscribed.remove(user.id());
        entity.setSubscribedUsers(subscribed);
        if (entitySaveable(entity, railsNameOf(entity))) {
            entityManager.flush();
        }
        return jsonWriter.writeOne(resource(family), id);
    }

    /**
     * {@code hasPermission(id,'Model','update')} for the generic family mappings: nonexistent row
     * → 404, row outside the accessible scope → 403 (Rails 401; the allow-list entry covers the
     * documented status gap).
     */
    @SuppressWarnings("unchecked")
    public void authorize(AuthenticatedUser user, long id, String model) {
        Class<?> type = RailsModelType.fromRailsName(model)
            .orElseThrow(() -> new IllegalArgumentException(model)).entityClass();
        Object entity = entityManager.find(type, id);
        if (entity == null) {
            throw new EntityNotFoundException(model + " " + id);
        }
        if (!CrmAccessPolicy.supports(type)) {
            throw new org.springframework.security.access.AccessDeniedException(model);
        }
        var cb = entityManager.getCriteriaBuilder();
        var query = cb.createQuery(Long.class);
        var root = (jakarta.persistence.criteria.Root<Object>) query.from(type);
        Specification<Object> spec = accessPolicy.accessibleBy(user, (Class<Object>) type);
        query.select(cb.count(root)).where(
            cb.equal(root.get("id"), id), spec.toPredicate(root, query, cb));
        if (entityManager.createQuery(query).getSingleResult() == 0) {
            throw new org.springframework.security.access.AccessDeniedException(model);
        }
    }

    private CrmEntity load(String family, long id) {
        CrmEntity entity = (CrmEntity) entityManager.find(entityClass(family), id);
        if (entity == null) {
            throw new EntityNotFoundException(family + " " + id);
        }
        return entity;
    }

    private Class<?> entityClass(String family) {
        return switch (family) {
            case "accounts" -> Account.class;
            case "campaigns" -> Campaign.class;
            case "contacts" -> Contact.class;
            case "leads" -> Lead.class;
            case "opportunities" -> Opportunity.class;
            default -> throw new IllegalArgumentException(family);
        };
    }

    private com.fatfreecrm.service.json.RailsResource resource(String family) {
        return switch (family) {
            case "accounts" -> railsResources.account;
            case "campaigns" -> railsResources.campaign;
            case "contacts" -> railsResources.contact;
            case "leads" -> railsResources.lead;
            case "opportunities" -> railsResources.opportunity;
            default -> throw new IllegalArgumentException(family);
        };
    }

    private void applyTagListIfProvided(CrmEntity entity, String railsName,
        Map<String, JsonNode> params) {
        if (params.containsKey("tag_list")) {
            applyTagList(entity, railsName, params.get("tag_list"));
        }
    }

    static JsonNode field(JsonNode request, String key) {
        return request == null ? null : request.get(key);
    }

}
