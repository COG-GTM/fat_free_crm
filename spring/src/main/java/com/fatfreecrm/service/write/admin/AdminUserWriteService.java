package com.fatfreecrm.service.write.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.security.AuthlogicSha512PasswordEncoder;
import com.fatfreecrm.service.audit.EntityAttributes;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.validation.ActiveModelMessages;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.service.write.RailsParams;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rails {@code Admin::UsersController} writes (create/update/destroy/suspend/reactivate) plus the
 * Devise {@code database_authenticatable}/{@code encryptable} (Authlogic SHA-512, AB-264),
 * {@code confirmable} (token on create, {@code reconfirmable} email postponement on update) and
 * {@code User} model validations/callbacks. Confirmation e-mails are not sent (see ADR Phase B).
 */
@Service
public class AdminUserWriteService {

    private static final String MODEL = "user";
    private static final Pattern EMAIL_FORMAT =
        Pattern.compile("\\A([^@\\s]+@((?:[-a-z0-9]+\\.)+[a-z]{2,}))\\z", Pattern.CASE_INSENSITIVE);
    private static final Pattern USERNAME_FORMAT =
        Pattern.compile("\\A[a-z0-9_-]+\\z", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Object> USER_DEFAULTS = Map.of(
        "username", "", "encrypted_password", "", "password_salt", "", "sign_in_count", 0,
        "admin", false, "subscribe_to_comment_replies", true, "receive_assigned_notifications", true);
    private static final List<String> ASSET_TABLES =
        List.of("accounts", "campaigns", "leads", "contacts", "opportunities", "tasks");

    private final UserRepository userRepository;
    private final VersionRecorder versionRecorder;
    private final ActiveModelMessages messages;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate immediateTransaction;
    private final AuthlogicSha512PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AdminUserWriteService(
        UserRepository userRepository,
        VersionRecorder versionRecorder,
        ActiveModelMessages messages,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        Clock clock,
        @Value("${ffcrm.security.legacy-password.stretches:20}") int stretches
    ) {
        this.userRepository = userRepository;
        this.versionRecorder = versionRecorder;
        this.messages = messages;
        this.jdbcTemplate = jdbcTemplate;
        this.immediateTransaction = new TransactionTemplate(transactionManager);
        this.immediateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.passwordEncoder = new AuthlogicSha512PasswordEncoder(stretches);
        this.clock = clock;
    }

    /** Rails {@code User.new(user_params)} + {@code suspend_if_needs_approval} + save. */
    @Transactional
    public User create(AuthenticatedUser current, RailsParams params) {
        User user = new User();
        user.setSubscribeToCommentReplies(true);
        user.setReceiveAssignedNotifications(true);
        Credentials credentials = apply(user, params);
        Instant now = now();
        if (!user.isAdmin() && userSignupNeedsApproval()) {
            user.setSuspendedAt(now);
        }
        validate(user, credentials, true);
        user.setConfirmationToken(RailsTokens.friendlyToken());
        // A distinct Time object in Rails: Psych does not anchor it to created_at.
        user.setConfirmationSentAt(java.time.Instant.ofEpochSecond(now.getEpochSecond(), now.getNano()));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user = userRepository.saveAndFlush(user);
        if (params.provided("group_ids")) {
            replaceGroups(user.getId(), groupIds(params.get("group_ids").orElse(null)));
        }
        versionRecorder.recordCreate(current, user, EntityAttributes.of(user), USER_DEFAULTS);
        return user;
    }

    /** Rails {@code @user.attributes = user_params; @user.save}. */
    @Transactional
    public void update(AuthenticatedUser current, long id, RailsParams params) {
        User user = find(id);
        Map<String, Object> before = EntityAttributes.of(user);
        if (params.provided("group_ids")) {
            // habtm ids= on a persisted record writes the join rows immediately, outside the save
            // (they persist even when validation then fails).
            List<Long> groupIds = groupIds(params.get("group_ids").orElse(null));
            immediateTransaction.executeWithoutResult(status -> replaceGroups(id, groupIds));
        }
        Credentials credentials = apply(user, params);
        validate(user, credentials, false);
        String emailBefore = (String) before.get("email");
        if (!Objects.equals(emailBefore, user.getEmail()) && user.getEmail() != null
            && !user.getEmail().isEmpty()) {
            // Devise reconfirmable: postpone_email_change_until_confirmation_and_regenerate_confirmation_token
            user.setUnconfirmedEmail(user.getEmail());
            user.setEmail(emailBefore);
            user.setConfirmationToken(RailsTokens.friendlyToken());
            user.setConfirmationSentAt(now());
        }
        save(current, user, before, assignedOrder(params, credentials));
    }

    /**
     * PaperTrail object order: attributes assigned in {@code user_params} permit order ({@code password=}
     * sets salt then digest), then Devise's before_validation strip/downcase re-assigns {@code email}.
     */
    private static List<String> assignedOrder(RailsParams params, Credentials credentials) {
        List<String> order = new java.util.ArrayList<>();
        for (String key : List.of("admin", "username", "email", "first_name", "last_name", "title",
            "company", "alt_email", "phone", "mobile", "google")) {
            if (params.provided(key)) {
                order.add(key);
            }
        }
        if (credentials.password() != null && !credentials.password().isEmpty()) {
            order.add("password_salt");
            order.add("encrypted_password");
        }
        if (!order.contains("email")) {
            order.add("email");
        }
        return order;
    }

    /** Rails {@code destroy}: no-op (still 204) unless {@code destroyable?(current_user)}. */
    @Transactional
    public void destroy(AuthenticatedUser current, long id) {
        User user = find(id);
        if (Objects.equals(current.id(), user.getId()) || hasRelatedAssets(id)) {
            return;
        }
        Map<String, Object> attributes = EntityAttributes.of(user);
        versionRecorder.recordDestroy(current, user, attributes);
        jdbcTemplate.update("DELETE FROM avatars WHERE entity_type = 'User' AND entity_id = ?", id);
        jdbcTemplate.update("DELETE FROM permissions WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM preferences WHERE user_id = ?", id);
        jdbcTemplate.update("DELETE FROM groups_users WHERE user_id = ?", id);
        userRepository.delete(user);
        userRepository.flush();
    }

    /** Rails {@code update_attribute(:suspended_at, Time.now) if @user != current_user}. */
    @Transactional
    public void suspend(AuthenticatedUser current, long id) {
        User user = find(id);
        if (Objects.equals(current.id(), user.getId())) {
            return;
        }
        Map<String, Object> before = EntityAttributes.of(user);
        user.setSuspendedAt(now());
        save(current, user, before, List.of("suspended_at"));
    }

    /** Rails {@code update_attribute(:suspended_at, nil)}. */
    @Transactional
    public void reactivate(AuthenticatedUser current, long id) {
        User user = find(id);
        Map<String, Object> before = EntityAttributes.of(user);
        user.setSuspendedAt(null);
        save(current, user, before, List.of("suspended_at"));
    }

    private void save(AuthenticatedUser current, User user, Map<String, Object> before,
        List<String> assigned) {
        Map<String, Object> after = EntityAttributes.of(user);
        boolean changed = after.entrySet().stream()
            .anyMatch(entry -> !Objects.equals(before.get(entry.getKey()), entry.getValue()));
        if (!changed) {
            return;
        }
        user.setUpdatedAt(now());
        user = userRepository.saveAndFlush(user);
        versionRecorder.recordUpdate(current, user, before, EntityAttributes.of(user), assigned, true);
    }

    private User find(long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User " + id + " was not found"));
    }

    private record Credentials(String password, String passwordConfirmation) {
    }

    /** {@code user_params} permit list in order; Devise strips + downcases {@code email}. */
    private Credentials apply(User user, RailsParams params) {
        params.assignBoolean("admin", value -> user.setAdmin(Boolean.TRUE.equals(value)));
        params.assignString("username", user::setUsername);
        params.assignString("email", value -> user.setEmail(
            value == null ? null : value.strip().toLowerCase(Locale.ROOT)));
        params.assignString("first_name", user::setFirstName);
        params.assignString("last_name", user::setLastName);
        params.assignString("title", user::setTitle);
        params.assignString("company", user::setCompany);
        params.assignString("alt_email", value -> user.setAltEmail(value == null ? null : value.strip()));
        params.assignString("phone", user::setPhone);
        params.assignString("mobile", user::setMobile);
        params.assignString("google", user::setGoogle);
        String password = params.provided("password")
            ? RailsParams.asString(params.get("password").orElse(null)) : null;
        String confirmation = params.provided("password_confirmation")
            ? RailsParams.asString(params.get("password_confirmation").orElse(null)) : null;
        if (confirmation != null && confirmation.isBlank()) {
            confirmation = null; // controller: blank password_confirmation → nil
        }
        if (password != null && !password.isEmpty()) {
            String salt = RailsTokens.friendlyToken().substring(0, 20);
            user.setPasswordSalt(salt);
            user.setEncryptedPassword(passwordEncoder.digest(password, salt));
        }
        return new Credentials(password, confirmation);
    }

    private void validate(User user, Credentials credentials, boolean onCreate) {
        RailsErrors errors = new RailsErrors();
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            errors.add(messages, MODEL, "email", "missing_email");
        }
        int emailLength = email == null ? 0 : email.length();
        if (emailLength < 3) {
            errors.add("email", messages.generateMessage(MODEL, "email", "too_short.other",
                Map.of("count", 3)));
        } else if (emailLength > 254) {
            errors.add("email", messages.generateMessage(MODEL, "email", "too_long.other",
                Map.of("count", 254)));
        }
        if (taken("email", email, user.getId())) {
            errors.add(messages, MODEL, "email", "email_in_use");
        }
        if (onCreate && (email == null || !EMAIL_FORMAT.matcher(email).matches())) {
            errors.add(messages, MODEL, "email", "invalid");
        }
        String username = user.getUsername();
        if (taken("username", username, user.getId())) {
            errors.add(messages, MODEL, "username", "username_taken");
        }
        if (username == null || username.isBlank()) {
            errors.add(messages, MODEL, "username", "missing_username");
        }
        if (username == null || !USERNAME_FORMAT.matcher(username).matches()) {
            errors.add(messages, MODEL, "username", "invalid");
        }
        if (user.getGoogle() != null && user.getGoogle().length() > 32) {
            errors.add("google", messages.generateMessage(MODEL, "google", "too_long.other",
                Map.of("count", 32)));
        }
        boolean passwordRequired = onCreate || credentials.password() != null
            || credentials.passwordConfirmation() != null;
        if (passwordRequired && (credentials.password() == null || credentials.password().isBlank())) {
            errors.add(messages, MODEL, "password", "blank");
        }
        if (credentials.passwordConfirmation() != null
            && !credentials.passwordConfirmation().equals(credentials.password())) {
            // %{attribute} interpolates User.human_attribute_name(:password).
            errors.add("password_confirmation",
                messages.generateMessage(MODEL, "password", "confirmation", null));
        }
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    /** {@code uniqueness: {case_sensitive: false}} → {@code LOWER(col) = LOWER(?)}, self excluded. */
    private boolean taken(String column, String value, Long selfId) {
        String sql = value == null
            ? "SELECT count(*) FROM users WHERE " + column + " IS NULL"
            : "SELECT count(*) FROM users WHERE lower(" + column + ") = lower(?)";
        List<Object> args = new ArrayList<>();
        if (value != null) {
            args.add(value);
        }
        if (selfId != null) {
            sql += " AND id <> ?";
            args.add(selfId);
        }
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return count != null && count > 0;
    }

    /** {@code User#has_related_assets?}: assigned_to/created_by on assets, created_by on comments. */
    private boolean hasRelatedAssets(long id) {
        for (String table : ASSET_TABLES) {
            Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE assigned_to = ? OR user_id = ?",
                Long.class, id, id);
            if (count != null && count > 0) {
                return true;
            }
        }
        Long comments = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM comments WHERE user_id = ?", Long.class, id);
        return comments != null && comments > 0;
    }

    /** Rails {@code Setting.user_signup == :needs_approval} (DB row; YAML default is :not_allowed). */
    private boolean userSignupNeedsApproval() {
        List<String> values = jdbcTemplate.queryForList(
            "SELECT value FROM settings WHERE name = 'user_signup'", String.class);
        return !values.isEmpty() && values.get(0) != null
            && values.get(0).strip().equals("--- :needs_approval");
    }

    private List<Long> groupIds(JsonNode node) {
        Set<Long> ids = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String text = item.isNull() ? "" : item.asText().strip();
                if (!text.isEmpty()) {
                    ids.add(Long.parseLong(text));
                }
            }
        }
        for (Long groupId : ids) {
            Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM groups WHERE id = ?", Long.class, groupId);
            if (count == null || count == 0) {
                throw new EntityNotFoundException("Group " + groupId + " was not found");
            }
        }
        return new ArrayList<>(ids);
    }

    private void replaceGroups(long userId, List<Long> groupIds) {
        List<Long> existing = jdbcTemplate.queryForList(
            "SELECT group_id FROM groups_users WHERE user_id = ?", Long.class, userId);
        for (Long groupId : existing) {
            if (!groupIds.contains(groupId)) {
                jdbcTemplate.update("DELETE FROM groups_users WHERE user_id = ? AND group_id = ?",
                    userId, groupId);
            }
        }
        for (Long groupId : groupIds) {
            if (!existing.contains(groupId)) {
                jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)",
                    groupId, userId);
            }
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
