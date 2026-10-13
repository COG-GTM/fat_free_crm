package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.SavedList;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Column-order attribute dumps must match the Rails schema order PaperTrail serializes. */
class EntityAttributesTest {

    @Test
    void taskDumpFollowsSchemaOrderAndFlattensUserForeignKeys() {
        Task task = new Task();
        User owner = new User();
        ReflectionTestUtils.setField(owner, "id", 2L);
        task.setUser(owner);
        task.setName("Call");
        task.setSubscribedUsers(new ArrayList<>());

        Map<String, Object> attributes = EntityAttributes.of(task);

        assertThat(attributes.keySet()).containsExactly("id", "user_id", "assigned_to", "completed_by", "name",
            "asset_type", "asset_id", "priority", "category", "bucket", "due_at", "completed_at", "deleted_at",
            "created_at", "updated_at", "background_info", "subscribed_users");
        assertThat(attributes.get("user_id")).isEqualTo(2L);
        assertThat(attributes.get("assigned_to")).isNull();
        assertThat(attributes).containsEntry("subscribed_users", null);
    }

    @Test
    void taskSubscribedUsersAreDumpedAsTheListWhenPresent() {
        Task task = new Task();
        task.setSubscribedUsers(new ArrayList<>(List.of(2L, 3L)));
        assertThat(EntityAttributes.of(task).get("subscribed_users")).isEqualTo(List.of(2L, 3L));
    }

    @Test
    void commentEmailAndListDumpsFollowSchemaOrder() {
        Comment comment = new Comment();
        comment.setCommentableType("Account");
        assertThat(EntityAttributes.of(comment).keySet()).containsExactly("id", "user_id", "commentable_type",
            "commentable_id", "private", "title", "comment", "created_at", "updated_at", "state");
        assertThat(EntityAttributes.of(comment).get("state")).isEqualTo("Expanded");

        assertThat(EntityAttributes.of(new Email()).keySet()).containsExactly("id", "imap_message_id", "user_id",
            "mediator_type", "mediator_id", "sent_from", "sent_to", "cc", "bcc", "subject", "body", "header",
            "sent_at", "received_at", "deleted_at", "created_at", "updated_at", "state");

        assertThat(EntityAttributes.of(new SavedList()).keySet())
            .containsExactly("id", "name", "url", "created_at", "updated_at", "user_id");
    }

    @Test
    void unmappedEntitiesAreRejected() {
        assertThatThrownBy(() -> EntityAttributes.of(new Account()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Account");
    }
}
