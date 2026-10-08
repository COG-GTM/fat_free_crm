package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.SavedList;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins {@link EntityAttributes} to the {@code db/schema.rb} column order PaperTrail dumps for
 * {@code versions.object}: {@code id} first, then the columns in declaration order (note
 * {@code lists.user_id} is the last column), user associations as raw {@code *_id} values, and
 * the serialized {@code subscribed_users} array as nil when empty.
 */
class EntityAttributesTest {

    private static final Instant T = Instant.parse("2026-10-08T03:04:05Z");

    private static User user(long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void taskFollowsTasksTableColumnOrder() {
        Task task = new Task();
        ReflectionTestUtils.setField(task, "id", 601L);
        task.setUser(user(1L));
        task.setAssignedTo(user(2L));
        task.setName("Call Alice");
        task.setAssetType("Account");
        task.setAssetId(55);
        task.setBucket("due_today");
        task.setDueAt(T);
        task.setCreatedAt(T);
        task.setSubscribedUsers(List.of(2L, 1L));

        Map<String, Object> attributes = EntityAttributes.of(task);

        assertThat(attributes.keySet()).containsExactly(
            "id", "user_id", "assigned_to", "completed_by", "name", "asset_type", "asset_id", "priority",
            "category", "bucket", "due_at", "completed_at", "deleted_at", "created_at", "updated_at",
            "background_info", "subscribed_users");
        assertThat(attributes).containsEntry("id", 601L)
            .containsEntry("user_id", 1L)
            .containsEntry("assigned_to", 2L)
            .containsEntry("completed_by", null)
            .containsEntry("asset_type", "Account")
            .containsEntry("asset_id", 55)
            .containsEntry("due_at", T)
            .containsEntry("subscribed_users", List.of(2L, 1L));
    }

    @Test
    void emptySubscribedUsersSerializesAsNil() {
        Task task = new Task();
        ReflectionTestUtils.setField(task, "id", 601L);

        assertThat(EntityAttributes.of(task)).containsEntry("subscribed_users", null)
            .containsEntry("user_id", null);
    }

    @Test
    void commentFollowsCommentsTableColumnOrder() {
        Comment comment = new Comment();
        ReflectionTestUtils.setField(comment, "id", 801L);
        comment.setUser(user(1L));
        comment.setCommentableType("Task");
        comment.setCommentableId(601);
        comment.setTitle("");
        comment.setComment("hello");
        comment.setState("Expanded");

        Map<String, Object> attributes = EntityAttributes.of(comment);

        assertThat(attributes.keySet()).containsExactly(
            "id", "user_id", "commentable_type", "commentable_id", "private", "title", "comment",
            "created_at", "updated_at", "state");
        assertThat(attributes).containsEntry("user_id", 1L)
            .containsEntry("commentable_type", "Task")
            .containsEntry("commentable_id", 601)
            .containsEntry("state", "Expanded");
    }

    @Test
    void emailFollowsEmailsTableColumnOrder() {
        Email email = new Email();
        ReflectionTestUtils.setField(email, "id", 701L);
        email.setImapMessageId("<msg@example.com>");
        email.setUser(user(1L));
        email.setMediatorType("Task");
        email.setMediatorId(601);
        email.setSentFrom("alice@example.com");
        email.setSentTo("bob@example.com");
        email.setSubject("Hello");
        email.setState("Expanded");

        Map<String, Object> attributes = EntityAttributes.of(email);

        assertThat(attributes.keySet()).containsExactly(
            "id", "imap_message_id", "user_id", "mediator_type", "mediator_id", "sent_from", "sent_to", "cc",
            "bcc", "subject", "body", "header", "sent_at", "received_at", "deleted_at", "created_at",
            "updated_at", "state");
        assertThat(attributes).containsEntry("user_id", 1L)
            .containsEntry("mediator_type", "Task")
            .containsEntry("mediator_id", 601)
            .containsEntry("cc", null);
    }

    @Test
    void savedListFollowsListsTableColumnOrderWithUserIdLast() {
        SavedList list = new SavedList();
        ReflectionTestUtils.setField(list, "id", 901L);
        list.setName("Hot leads");
        list.setUrl("/leads?x=1");
        list.setUser(user(1L));

        Map<String, Object> attributes = EntityAttributes.of(list);

        assertThat(attributes.keySet()).containsExactly("id", "name", "url", "created_at", "updated_at", "user_id");
        assertThat(attributes).containsEntry("user_id", 1L).containsEntry("url", "/leads?x=1");
    }

    @Test
    void unmappedEntitiesAreRejected() {
        assertThatThrownBy(() -> EntityAttributes.of(new Account()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Account");
    }
}
