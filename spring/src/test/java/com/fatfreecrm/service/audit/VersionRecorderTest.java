package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Comment;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.repository.VersionRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VersionRecorderTest {

    private final VersionRepository versionRepository = mock(VersionRepository.class);
    private final ColumnDefaults columnDefaults = mock(ColumnDefaults.class);
    private final VersionRecorder versionRecorder = new VersionRecorder(
        versionRepository,
        mock(EntityManager.class),
        Clock.fixed(Instant.parse("2025-02-01T12:00:00Z"), ZoneOffset.UTC),
        columnDefaults);

    @BeforeEach
    void returnSavedVersion() {
        when(versionRepository.save(any(Version.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(columnDefaults.defaults(any(Object.class))).thenReturn(Map.of());
    }

    @Test
    void recordsTheRelatedAddressableForAddressVersions() {
        Address address = mock(Address.class);
        when(address.getId()).thenReturn(17L);
        when(address.getAddressableType()).thenReturn("Account");
        when(address.getAddressableId()).thenReturn(42);

        Version version = versionRecorder.recordCreate(null, address, Map.of("id", 17L), Map.of());

        assertThat(version.getItemType()).isEqualTo("Address");
        assertThat(version.getRelatedType()).isEqualTo("Account");
        assertThat(version.getRelatedId()).isEqualTo(42);
    }

    @Test
    void recordsTheContactAssociationForAccountContactVersions() {
        Contact contact = mock(Contact.class);
        when(contact.getId()).thenReturn(23L);
        AccountContact accountContact = mock(AccountContact.class);
        when(accountContact.getId()).thenReturn(19L);
        when(accountContact.getContact()).thenReturn(contact);

        Version version = versionRecorder.recordCreate(
            null, accountContact, Map.of("id", 19L), Map.of());

        assertThat(version.getItemType()).isEqualTo("AccountContact");
        assertThat(version.getRelatedType()).isEqualTo("Contact");
        assertThat(version.getRelatedId()).isEqualTo(23);
    }

    @Test
    void recordsUserVersionsUsingItsPrimaryKey() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(68_999L);

        Version version = versionRecorder.recordCreate(null, user, Map.of("id", 68_999L), Map.of());

        assertThat(version.getItemType()).isEqualTo("User");
        assertThat(version.getItemId()).isEqualTo(68_999);
    }

    @Test
    void usesTheDatabaseCommentTitleDefaultWhenCallerDefaultsOmitIt() {
        Comment comment = mock(Comment.class);
        when(comment.getId()).thenReturn(7L);
        when(columnDefaults.defaults(comment)).thenReturn(Map.of("title", ""));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", 7L);
        attributes.put("title", "");
        attributes.put("comment", "New account comment");

        Version version = versionRecorder.recordCreate(null, comment, attributes, Map.of());

        assertThat(version.getObjectChanges())
            .isEqualTo("---\nid:\n-\n- 7\ncomment:\n-\n- New account comment\n");
    }

    @Test
    void recordsATouchAsAnUpdateWithTheTouchedObjectAndNoChanges() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(31L);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", "before");
        before.put("updated_at", Instant.parse("2025-02-01T11:59:00Z"));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("updated_at", Instant.parse("2025-02-01T12:00:00Z"));

        Version version = versionRecorder.recordTouch(null, account, before, after);

        assertThat(version.getEvent()).isEqualTo("update");
        assertThat(version.getObject()).startsWith("---\nname: before\nupdated_at: ");
        assertThat(version.getObject()).contains("utc: 2025-02-01 12:00:00.000000000 Z");
        assertThat(version.getObjectChanges()).isNull();
    }

    @Test
    void doesNotRecordTouchesWhoseOnlyChangeIsIgnored() {
        AccountContact association = mock(AccountContact.class);
        when(association.getId()).thenReturn(41L);
        Map<String, Object> before = Map.of("updated_at", Instant.parse("2025-02-01T11:59:00Z"));
        Map<String, Object> after = Map.of("updated_at", Instant.parse("2025-02-01T12:00:00Z"));

        assertThat(versionRecorder.recordTouch(null, association, before, after)).isNull();
        org.mockito.Mockito.verify(versionRepository, never()).save(any(Version.class));
    }

    @Test
    void recordsTouchWhenTheTimestampIsUnchanged() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(43L);
        Instant touchedAt = Instant.parse("2025-02-01T12:00:00Z");
        Map<String, Object> attributes = Map.of("updated_at", touchedAt);

        Version version = versionRecorder.recordTouch(null, account, attributes, attributes);

        assertThat(version.getEvent()).isEqualTo("update");
        assertThat(version.getObjectChanges()).isNull();
    }

    @Test
    void movesAssignedUpdateAttributesFirstAndKeepsChangesColumnOrdered() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(42L);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", 42L);
        before.put("name", "Before");
        before.put("access", "Public");
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("name", "After");
        after.put("access", "Private");

        Version version = versionRecorder.recordUpdate(null, account, before, after, List.of("access", "name"));

        assertThat(version.getObject()).isEqualTo("---\naccess: Public\nname: Before\nid: 42\n");
        assertThat(version.getObjectChanges()).startsWith("---\nname:\n- Before\n- After\naccess:");
    }

    @Test
    void usesSeparateDirtyTrackingBeforeValueForVirtualTagListChanges() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(46L);
        PaperTrailYaml.RubyTagList objectTagList = new PaperTrailYaml.RubyTagList(List.of("alpha", "beta"));
        Map<String, Object> objectBefore = new LinkedHashMap<>();
        objectBefore.put("tag_list", objectTagList);
        Map<String, Object> changeBefore = new LinkedHashMap<>();
        changeBefore.put("tag_list", List.of());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("tag_list", List.of("alpha", "beta"));

        Version version = versionRecorder.recordUpdate(
            null, account, objectBefore, after, List.of("tag_list"), changeBefore);

        assertThat(version.getObject()).contains(
            "tag_list: !ruby/array:ActsAsTaggableOn::TagList\n"
                + "  internal:\n  - alpha\n  - beta\n"
                + "  ivars:\n    :@parser: !ruby/class 'ActsAsTaggableOn::DefaultParser'\n");
        assertThat(version.getObjectChanges()).isEqualTo(
            "---\ntag_list:\n- []\n- - alpha\n  - beta\n");
    }

    @Test
    void recordsAnExplicitUpdatedAtOnlyChange() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(44L);
        Map<String, Object> before = Map.of("updated_at", Instant.parse("2025-02-01T11:59:00Z"));
        Map<String, Object> after = Map.of("updated_at", Instant.parse("2025-02-01T12:00:00Z"));

        Version version = versionRecorder.recordUpdate(null, account, before, after, List.of("updated_at"));

        assertThat(version.getObject()).startsWith("---\nupdated_at: ");
        assertThat(version.getObjectChanges()).startsWith("---\nupdated_at:\n");
    }

    @Test
    void doesNotRecordAnImplicitUpdatedAtOnlyChange() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(45L);
        Map<String, Object> before = Map.of("updated_at", Instant.parse("2025-02-01T11:59:00Z"));
        Map<String, Object> after = Map.of("updated_at", Instant.parse("2025-02-01T12:00:00Z"));

        assertThat(versionRecorder.recordUpdate(null, account, before, after, List.of())).isNull();
    }
}
