package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.SavedList;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.VersionRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pins {@link VersionRecorder} to PaperTrail 16 semantics observed in Rails: create rows carry
 * only non-default attributes in {@code object_changes} and no {@code object}; update rows are
 * skipped when only timestamps/ignored attributes changed; destroy rows pair every non-ignored
 * attribute with nil; {@code meta: {related: ...}} copies the polymorphic parent; TaskObserver
 * event rows have no object/changes/related; {@code whodunnit} is the user id string and
 * {@code created_at} comes from the injected clock at microsecond precision.
 */
class VersionRecorderTest {

    private static final Instant NOW = Instant.parse("2026-10-08T03:04:05.123456789Z");
    private static final AuthenticatedUser ALICE = new AuthenticatedUser(7L, "alice", false);

    private VersionRepository versionRepository;
    private VersionRecorder recorder;

    @BeforeEach
    void setUp() {
        versionRepository = mock(VersionRepository.class);
        when(versionRepository.save(any(Version.class))).thenAnswer(invocation -> invocation.getArgument(0));
        recorder = new VersionRecorder(versionRepository, null, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User user(long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Task task() {
        Task task = new Task();
        ReflectionTestUtils.setField(task, "id", 601L);
        task.setUser(user(7L));
        task.setName("Call Alice");
        task.setAssetType("Account");
        task.setAssetId(55);
        task.setBucket("due_today");
        task.setSubscribedUsers(List.of(9L));
        return task;
    }

    @Test
    void createVersionListsOnlyNonDefaultNonIgnoredAttributesWithNoObject() {
        Task task = task();

        Version version = recorder.recordCreate(ALICE, task, EntityAttributes.of(task), Map.of("name", ""));

        assertThat(version.getItemType()).isEqualTo("Task");
        assertThat(version.getItemId()).isEqualTo(601);
        assertThat(version.getEvent()).isEqualTo("create");
        assertThat(version.getWhodunnit()).isEqualTo("7");
        assertThat(version.getObject()).isNull();
        assertThat(version.getRelatedType()).isEqualTo("Account");
        assertThat(version.getRelatedId()).isEqualTo(55);
        assertThat(version.getTransactionId()).isNull();
        assertThat(version.getCreatedAt()).isEqualTo(Instant.parse("2026-10-08T03:04:05.123456Z"));

        String changes = version.getObjectChanges();
        assertThat(changes).startsWith("---\nid:\n-\n- 601\nuser_id:\n-\n- 7\n");
        assertThat(changes).contains("name:\n- ''\n- Call Alice\n");
        assertThat(changes).contains("bucket:\n-\n- due_today\n");
        assertThat(changes).doesNotContain("assigned_to").doesNotContain("subscribed_users")
            .doesNotContain("completed_at");
        verify(versionRepository).save(version);
    }

    @Test
    void createVersionOmitsAttributesEqualToTheirColumnDefault() {
        Task task = task();
        task.setName("");

        Version version = recorder.recordCreate(ALICE, task, EntityAttributes.of(task), Map.of("name", ""));

        assertThat(version.getObjectChanges()).doesNotContain("name:");
    }

    @Test
    void updateWithOnlyTimestampOrIgnoredChangesWritesNoVersion() {
        Task task = task();
        Map<String, Object> before = new LinkedHashMap<>(EntityAttributes.of(task));
        before.put("updated_at", NOW.minusSeconds(60));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("updated_at", NOW);
        after.put("subscribed_users", List.of(9L, 7L));

        assertThat(recorder.recordUpdate(ALICE, task, before, after)).isNull();
        verify(versionRepository, never()).save(any());
    }

    @Test
    void updateObjectLeadsWithAssignedChangedAttributesThenColumnOrderAndChangesStayColumnOrdered() {
        Task task = task();
        Map<String, Object> before = new LinkedHashMap<>(EntityAttributes.of(task));
        before.put("priority", null);
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("name", "Call Bob");
        after.put("bucket", "due_tomorrow");
        after.put("updated_at", NOW);
        after.put("subscribed_users", List.of(9L, 7L));

        Version version = recorder.recordUpdate(ALICE, task, before, after, List.of("bucket", "name", "priority"));

        assertThat(version.getEvent()).isEqualTo("update");
        assertThat(version.getObject()).startsWith("---\nbucket: due_today\nname: Call Alice\nid: 601\nuser_id: 7\n");
        assertThat(version.getObject()).contains("subscribed_users:\n- 9\n");
        String changes = version.getObjectChanges();
        assertThat(changes).startsWith("---\nname:\n- Call Alice\n- Call Bob\nbucket:\n- due_today\n- due_tomorrow\n");
        assertThat(changes).contains("updated_at:\n-\n- !ruby/object:ActiveSupport::TimeWithZone\n");
        assertThat(changes).doesNotContain("subscribed_users").doesNotContain("priority");
    }

    @Test
    void destroyVersionDumpsObjectAndPairsNonIgnoredAttributesWithNil() {
        Comment comment = new Comment();
        ReflectionTestUtils.setField(comment, "id", 801L);
        comment.setUser(user(7L));
        comment.setCommentableType("Task");
        comment.setCommentableId(601);
        comment.setTitle("");
        comment.setComment("hello");
        comment.setState("Expanded");

        Version version = recorder.recordDestroy(ALICE, comment, EntityAttributes.of(comment));

        assertThat(version.getItemType()).isEqualTo("Comment");
        assertThat(version.getItemId()).isEqualTo(801);
        assertThat(version.getEvent()).isEqualTo("destroy");
        assertThat(version.getRelatedType()).isEqualTo("Task");
        assertThat(version.getRelatedId()).isEqualTo(601);
        assertThat(version.getObject()).isEqualTo(
            "---\nid: 801\nuser_id: 7\ncommentable_type: Task\ncommentable_id: 601\nprivate:\ntitle: ''\n"
                + "comment: hello\ncreated_at:\nupdated_at:\nstate: Expanded\n");
        assertThat(version.getObjectChanges()).contains("comment:\n- hello\n-\n").contains("title:\n- ''\n-\n")
            .doesNotContain("state:");
    }

    @Test
    void observerEventRowsHaveNoObjectChangesOrRelated() {
        Version version = recorder.recordEvent(ALICE, RailsModelType.TASK, 601L, "complete");

        assertThat(version.getItemType()).isEqualTo("Task");
        assertThat(version.getItemId()).isEqualTo(601);
        assertThat(version.getEvent()).isEqualTo("complete");
        assertThat(version.getWhodunnit()).isEqualTo("7");
        assertThat(version.getObject()).isNull();
        assertThat(version.getObjectChanges()).isNull();
        assertThat(version.getRelatedType()).isNull();
        assertThat(version.getRelatedId()).isNull();
        assertThat(version.getCreatedAt()).isEqualTo(Instant.parse("2026-10-08T03:04:05.123456Z"));
    }

    @Test
    void whodunnitIsNilWithoutAnAuthenticatedUser() {
        assertThat(recorder.recordEvent(null, RailsModelType.TASK, 601L, "reschedule").getWhodunnit()).isNull();
    }

    @Test
    void taskWithoutAssetLeavesRelatedNil() {
        Task task = task();
        task.setAssetType(null);
        task.setAssetId(null);

        Version version = recorder.recordDestroy(ALICE, task, EntityAttributes.of(task));

        assertThat(version.getRelatedType()).isNull();
        assertThat(version.getRelatedId()).isNull();
    }

    @Test
    void savedListsHaveNoPaperTrailLikeRails() {
        SavedList list = new SavedList();
        ReflectionTestUtils.setField(list, "id", 901L);

        assertThatThrownBy(() -> recorder.recordCreate(ALICE, list, EntityAttributes.of(list), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        verify(versionRepository, never()).save(any());
    }
}
