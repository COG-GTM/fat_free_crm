package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;
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

/**
 * Phase B additions to the PaperTrail recorder: {@code User} ({@code has_paper_trail ignore:
 * [:last_sign_in_at]}, non-{@code BaseEntity} id) and {@code leadUnchangedAssigned} (Devise
 * re-assigns {@code email} in before_validation, so it leads the dumped object even when unchanged).
 */
class VersionRecorderUserTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07.123456789Z");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "root_admin", true);

    private VersionRecorder recorder;

    @BeforeEach
    void setUp() {
        VersionRepository versionRepository = mock(VersionRepository.class);
        when(versionRepository.save(any(Version.class))).thenAnswer(invocation -> invocation.getArgument(0));
        recorder = new VersionRecorder(versionRepository, mock(EntityManager.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void userVersionsUseUserItemTypeAndIdAndSkipIgnoredAndDefaultEqualColumns() {
        User user = user(42L);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", 42L);
        attributes.put("username", "dave");
        attributes.put("admin", false);
        attributes.put("last_sign_in_at", Instant.parse("2026-01-01T00:00:00Z"));
        attributes.put("subscribe_to_comment_replies", true);
        attributes.put("suspended_at", null);

        Version version = recorder.recordCreate(ADMIN, user, attributes,
            Map.of("username", "", "admin", false, "subscribe_to_comment_replies", true));

        assertThat(version.getItemType()).isEqualTo("User");
        assertThat(version.getItemId()).isEqualTo(42);
        assertThat(version.getEvent()).isEqualTo("create");
        assertThat(version.getWhodunnit()).isEqualTo("1");
        assertThat(version.getRelatedType()).isNull();
        assertThat(version.getObject()).isNull();
        assertThat(version.getObjectChanges()).isEqualTo("---\nid:\n-\n- 42\nusername:\n- ''\n- dave\n");
    }

    @Test
    void lastSignInAtOnlyChangesWriteNoUpdateVersion() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", 42L);
        before.put("last_sign_in_at", null);
        before.put("updated_at", Instant.parse("2026-01-01T00:00:00Z"));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("last_sign_in_at", NOW);
        after.put("updated_at", NOW);

        assertThat(recorder.recordUpdate(ADMIN, user(42L), before, after, List.of("last_sign_in_at"), true))
            .isNull();
    }

    @Test
    void leadUnchangedAssignedPutsReassignedEmailFirstInObjectButNotInObjectChanges() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", 42L);
        before.put("username", "bob");
        before.put("email", "bob@example.test");
        before.put("first_name", "Bob");
        before.put("unconfirmed_email", null);
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("first_name", "Bobby");
        after.put("unconfirmed_email", "bob2@example.test");
        List<String> assigned = List.of("email", "first_name");

        Version led = recorder.recordUpdate(ADMIN, user(42L), before, after, assigned, true);
        Version plain = recorder.recordUpdate(ADMIN, user(42L), before, after, assigned, false);

        assertThat(led.getEvent()).isEqualTo("update");
        assertThat(led.getItemType()).isEqualTo("User");
        assertThat(led.getObject()).isEqualTo(
            "---\nemail: bob@example.test\nfirst_name: Bob\nid: 42\nusername: bob\nunconfirmed_email:\n");
        assertThat(plain.getObject()).isEqualTo(
            "---\nfirst_name: Bob\nid: 42\nusername: bob\nemail: bob@example.test\nunconfirmed_email:\n");
        String changes = "---\nfirst_name:\n- Bob\n- Bobby\nunconfirmed_email:\n-\n- bob2@example.test\n";
        assertThat(led.getObjectChanges()).isEqualTo(changes);
        assertThat(plain.getObjectChanges()).isEqualTo(changes);
    }

    @Test
    void destroyVersionDumpsAllAttributesButOmitsIgnoredOnesFromChanges() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", 42L);
        attributes.put("username", "carol");
        attributes.put("last_sign_in_at", NOW);

        Version version = recorder.recordDestroy(ADMIN, user(42L), attributes);

        assertThat(version.getEvent()).isEqualTo("destroy");
        assertThat(version.getItemId()).isEqualTo(42);
        // PaperTrail `ignore` filters object_changes only; the `object` dump keeps every attribute
        assertThat(version.getObject())
            .startsWith("---\nid: 42\nusername: carol\nlast_sign_in_at: !ruby/object:ActiveSupport::TimeWithZone\n");
        assertThat(version.getObjectChanges()).isEqualTo("---\nid:\n- 42\n-\nusername:\n- carol\n-\n");
    }

    private static User user(long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername("u" + id);
        return user;
    }
}
