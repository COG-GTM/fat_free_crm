package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Email;
import com.fatfreecrm.domain.SavedList;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.VersionRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** PaperTrail 16 semantics pinned per {@code has_paper_trail} options of the AB-272 write families. */
class VersionRecorderTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07.123456789Z");
    private static final AuthenticatedUser ALICE = new AuthenticatedUser(2L, "alice", false);

    private VersionRepository versionRepository;
    private VersionRecorder recorder;

    @BeforeEach
    void setUp() {
        versionRepository = mock(VersionRepository.class);
        when(versionRepository.save(any(Version.class))).thenAnswer(invocation -> invocation.getArgument(0));
        recorder = new VersionRecorder(versionRepository, mock(EntityManager.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createVersionOmitsDefaultEqualAndIgnoredAttributesAndCopiesAssetAsRelated() {
        Task task = task(606L, "Account", 101);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", 606L);
        attributes.put("user_id", 2L);
        attributes.put("name", "");
        attributes.put("asset_type", "Account");
        attributes.put("priority", null);
        attributes.put("subscribed_users", List.of(2L));

        Version version = recorder.recordCreate(ALICE, task, attributes, Map.of("name", ""));

        assertThat(version.getItemType()).isEqualTo("Task");
        assertThat(version.getItemId()).isEqualTo(606);
        assertThat(version.getEvent()).isEqualTo("create");
        assertThat(version.getWhodunnit()).isEqualTo("2");
        assertThat(version.getRelatedType()).isEqualTo("Account");
        assertThat(version.getRelatedId()).isEqualTo(101);
        assertThat(version.getObject()).isNull();
        assertThat(version.getTransactionId()).isNull();
        assertThat(version.getCreatedAt()).isEqualTo(Instant.parse("2026-03-04T05:06:07.123456Z"));
        assertThat(version.getObjectChanges())
            .isEqualTo("---\nid:\n-\n- 606\nuser_id:\n-\n- 2\nasset_type:\n-\n- Account\n");
    }

    @Test
    void updateWithOnlyTimestampOrIgnoredChangesIsNotNotable() {
        Task task = task(606L, null, null);
        Map<String, Object> before = attributes("name", "Call", "updated_at", Instant.parse("2026-01-01T00:00:00Z"),
            "subscribed_users", null);
        Map<String, Object> after = attributes("name", "Call", "updated_at", NOW, "subscribed_users", List.of(2L));

        assertThat(recorder.recordUpdate(ALICE, task, before, after)).isNull();
        verify(versionRepository, never()).save(any());
    }

    @Test
    void updateLeadsObjectWithAssignedChangedAttributesAndKeepsChangesColumnOrdered() {
        Task task = task(606L, null, null);
        Map<String, Object> before = attributes("id", 606L, "name", "Old", "bucket", "due_asap", "priority", "low");
        Map<String, Object> after = attributes("id", 606L, "name", "New", "bucket", "due_today", "priority", "low");

        Version version = recorder.recordUpdate(ALICE, task, before, after, List.of("bucket", "priority", "name"));

        assertThat(version.getEvent()).isEqualTo("update");
        assertThat(version.getRelatedType()).isNull();
        assertThat(version.getObject()).isEqualTo("---\nbucket: due_asap\nname: Old\nid: 606\npriority: low\n");
        assertThat(version.getObjectChanges())
            .isEqualTo("---\nname:\n- Old\n- New\nbucket:\n- due_asap\n- due_today\n");
    }

    @Test
    void destroyDumpsEveryAttributeButPairsOnlyNonIgnoredOnesWithNil() {
        Email email = new Email();
        ReflectionTestUtils.setField(email, "id", 701L);
        email.setMediatorType("Contact");
        email.setMediatorId(203);
        Map<String, Object> attributes = attributes("id", 701L, "subject", "Hi", "state", "Expanded");

        Version version = recorder.recordDestroy(ALICE, email, attributes);

        assertThat(version.getItemType()).isEqualTo("Email");
        assertThat(version.getEvent()).isEqualTo("destroy");
        assertThat(version.getRelatedType()).isEqualTo("Contact");
        assertThat(version.getRelatedId()).isEqualTo(203);
        assertThat(version.getObject()).isEqualTo("---\nid: 701\nsubject: Hi\nstate: Expanded\n");
        assertThat(version.getObjectChanges()).isEqualTo("---\nid:\n- 701\n-\nsubject:\n- Hi\n-\n");
    }

    @Test
    void commentVersionsRelateToTheCommentable() {
        Comment comment = new Comment();
        ReflectionTestUtils.setField(comment, "id", 5L);
        comment.setCommentableType("Opportunity");
        comment.setCommentableId(401);

        Version version = recorder.recordCreate(ALICE, comment, attributes("comment", "x"), Map.of());

        assertThat(version.getItemType()).isEqualTo("Comment");
        assertThat(version.getRelatedType()).isEqualTo("Opportunity");
        assertThat(version.getRelatedId()).isEqualTo(401);
    }

    @Test
    void observerEventRowsCarryNoObjectOrRelatedAndNullWhodunnitWithoutUser() {
        Version version = recorder.recordEvent(null, RailsModelType.TASK, 606L, "reschedule");

        assertThat(version.getItemType()).isEqualTo("Task");
        assertThat(version.getItemId()).isEqualTo(606);
        assertThat(version.getEvent()).isEqualTo("reschedule");
        assertThat(version.getWhodunnit()).isNull();
        assertThat(version.getObject()).isNull();
        assertThat(version.getObjectChanges()).isNull();
        assertThat(version.getRelatedType()).isNull();
        assertThat(version.getRelatedId()).isNull();
        assertThat(version.getCreatedAt()).isEqualTo(Instant.parse("2026-03-04T05:06:07.123456Z"));
    }

    @Test
    void savedListHasNoPaperTrail() {
        SavedList list = new SavedList();
        ReflectionTestUtils.setField(list, "id", 801L);
        assertThatThrownBy(() -> recorder.recordDestroy(ALICE, list, attributes("id", 801L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SavedList");
    }

    private static Task task(Long id, String assetType, Integer assetId) {
        Task task = new Task();
        ReflectionTestUtils.setField(task, "id", id);
        task.setAssetType(assetType);
        task.setAssetId(assetId);
        return task;
    }

    private static Map<String, Object> attributes(Object... pairs) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            attributes.put((String) pairs[index], pairs[index + 1]);
        }
        return attributes;
    }
}
