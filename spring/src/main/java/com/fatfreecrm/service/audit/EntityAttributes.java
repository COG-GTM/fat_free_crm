package com.fatfreecrm.service.audit;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.SavedList;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dumps an entity's persisted attributes in the Rails column order PaperTrail uses for
 * {@code versions.object}, and computes the same attribute maps {@code object_changes} needs.
 * User foreign keys appear as the raw {@code <name>_id} column value. Serialized
 * {@code subscribed_users} appears as the deserialized array (a YAML sequence in Psych output),
 * and a NULL column serializes as nil.
 */
public final class EntityAttributes {

    private EntityAttributes() {
    }

    public static Map<String, Object> of(Object entity) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        switch (entity) {
            case Task task -> {
                attributes.put("id", task.getId());
                attributes.put("user_id", idOf(task.getUser()));
                attributes.put("assigned_to", idOf(task.getAssignedTo()));
                attributes.put("completed_by", idOf(task.getCompletedBy()));
                attributes.put("name", task.getName());
                attributes.put("asset_type", task.getAssetType());
                attributes.put("asset_id", task.getAssetId());
                attributes.put("priority", task.getPriority());
                attributes.put("category", task.getCategory());
                attributes.put("bucket", task.getBucket());
                attributes.put("due_at", task.getDueAt());
                attributes.put("completed_at", task.getCompletedAt());
                attributes.put("deleted_at", task.getDeletedAt());
                attributes.put("created_at", task.getCreatedAt());
                attributes.put("updated_at", task.getUpdatedAt());
                attributes.put("background_info", task.getBackgroundInfo());
                attributes.put("subscribed_users",
                    task.getSubscribedUsers().isEmpty() ? null : task.getSubscribedUsers());
            }
            case Comment comment -> {
                attributes.put("id", comment.getId());
                attributes.put("user_id", idOf(comment.getUser()));
                attributes.put("commentable_type", comment.getCommentableType());
                attributes.put("commentable_id", comment.getCommentableId());
                attributes.put("private", comment.getPrivate());
                attributes.put("title", comment.getTitle());
                attributes.put("comment", comment.getComment());
                attributes.put("created_at", comment.getCreatedAt());
                attributes.put("updated_at", comment.getUpdatedAt());
                attributes.put("state", comment.getState());
            }
            case Email email -> {
                attributes.put("id", email.getId());
                attributes.put("imap_message_id", email.getImapMessageId());
                attributes.put("user_id", idOf(email.getUser()));
                attributes.put("mediator_type", email.getMediatorType());
                attributes.put("mediator_id", email.getMediatorId());
                attributes.put("sent_from", email.getSentFrom());
                attributes.put("sent_to", email.getSentTo());
                attributes.put("cc", email.getCc());
                attributes.put("bcc", email.getBcc());
                attributes.put("subject", email.getSubject());
                attributes.put("body", email.getBody());
                attributes.put("header", email.getHeader());
                attributes.put("sent_at", email.getSentAt());
                attributes.put("received_at", email.getReceivedAt());
                attributes.put("deleted_at", email.getDeletedAt());
                attributes.put("created_at", email.getCreatedAt());
                attributes.put("updated_at", email.getUpdatedAt());
                attributes.put("state", email.getState());
            }
            case com.fatfreecrm.domain.User user -> {
                attributes.put("id", user.getId());
                attributes.put("username", user.getUsername());
                attributes.put("email", user.getEmail());
                attributes.put("first_name", user.getFirstName());
                attributes.put("last_name", user.getLastName());
                attributes.put("title", user.getTitle());
                attributes.put("company", user.getCompany());
                attributes.put("alt_email", user.getAltEmail());
                attributes.put("phone", user.getPhone());
                attributes.put("mobile", user.getMobile());
                attributes.put("google", user.getGoogle());
                attributes.put("encrypted_password", user.getEncryptedPassword());
                attributes.put("password_salt", user.getPasswordSalt());
                attributes.put("last_sign_in_at", user.getLastSignInAt());
                attributes.put("current_sign_in_at", user.getCurrentSignInAt());
                attributes.put("last_sign_in_ip", user.getLastSignInIp());
                attributes.put("current_sign_in_ip", user.getCurrentSignInIp());
                attributes.put("sign_in_count", user.getSignInCount());
                attributes.put("deleted_at", user.getDeletedAt());
                attributes.put("created_at", user.getCreatedAt());
                attributes.put("updated_at", user.getUpdatedAt());
                attributes.put("admin", user.isAdmin());
                attributes.put("suspended_at", user.getSuspendedAt());
                attributes.put("unconfirmed_email", user.getUnconfirmedEmail());
                attributes.put("reset_password_token", user.getResetPasswordToken());
                attributes.put("reset_password_sent_at", user.getResetPasswordSentAt());
                attributes.put("remember_token", user.getRememberToken());
                attributes.put("remember_created_at", user.getRememberCreatedAt());
                attributes.put("authentication_token", user.getAuthenticationToken());
                attributes.put("confirmation_token", user.getConfirmationToken());
                attributes.put("confirmed_at", user.getConfirmedAt());
                attributes.put("confirmation_sent_at", user.getConfirmationSentAt());
                attributes.put("subscribe_to_comment_replies", user.isSubscribeToCommentReplies());
                attributes.put("receive_assigned_notifications", user.isReceiveAssignedNotifications());
                attributes.put("zoom", user.getZoom());
                attributes.put("teams", user.getTeams());
                attributes.put("signal", user.getSignal());
                attributes.put("instagram", user.getInstagram());
                attributes.put("facebook", user.getFacebook());
                attributes.put("mastodon", user.getMastodon());
                attributes.put("bluesky", user.getBluesky());
                attributes.put("twitter", user.getTwitter());
                attributes.put("linkedin", user.getLinkedin());
                attributes.put("blog", user.getBlog());
            }
            case SavedList list -> {
                attributes.put("id", list.getId());
                attributes.put("name", list.getName());
                attributes.put("url", list.getUrl());
                attributes.put("created_at", list.getCreatedAt());
                attributes.put("updated_at", list.getUpdatedAt());
                attributes.put("user_id", idOf(list.getUser()));
            }
            default -> throw new IllegalArgumentException(
                "No PaperTrail attribute mapping for " + entity.getClass().getName());
        }
        return attributes;
    }

    static Long idOf(User user) {
        return user == null ? null : user.getId();
    }
}
