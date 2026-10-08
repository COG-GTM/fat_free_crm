package com.fatfreecrm.service.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.repository.VersionRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VersionRecorderTest {

    private final VersionRepository versionRepository = mock(VersionRepository.class);
    private final VersionRecorder versionRecorder = new VersionRecorder(
        versionRepository,
        mock(EntityManager.class),
        Clock.fixed(Instant.parse("2025-02-01T12:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void returnSavedVersion() {
        when(versionRepository.save(any(Version.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
}
