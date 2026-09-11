package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Contact;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * {@link ContactMapper} in isolation (the generated implementation, no Spring context): the two
 * type conversions and the column-to-field coercions the Rails {@code to_json} shape relies on.
 */
class ContactMapperTest {

    private final ContactMapper mapper = Mappers.getMapper(ContactMapper.class);

    @Test
    void timestampsAreReinterpretedAsUtcNotShifted() {
        LocalDateTime stored = LocalDateTime.of(2024, 5, 1, 9, 30, 15, 123_000_000);

        OffsetDateTime converted = mapper.utc(stored);

        assertThat(converted.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(converted.toLocalDateTime()).as("wall-clock value is preserved").isEqualTo(stored);
        assertThat(converted).isEqualTo(OffsetDateTime.parse("2024-05-01T09:30:15.123Z"));
        assertThat(mapper.utc(null)).isNull();
    }

    @Test
    void toDtoConvertsEveryTimestampAndKeepsNullDeletedAt() {
        Contact contact = new Contact();
        contact.setId(7L);
        contact.setCreatedAt(LocalDateTime.of(2024, 5, 1, 9, 30));
        contact.setUpdatedAt(LocalDateTime.of(2024, 5, 2, 10, 45));

        ContactDto dto = mapper.toDto(contact);

        assertThat(dto.id()).isEqualTo(7);
        assertThat(dto.createdAt()).isEqualTo(OffsetDateTime.parse("2024-05-01T09:30:00Z"));
        assertThat(dto.updatedAt()).isEqualTo(OffsetDateTime.parse("2024-05-02T10:45:00Z"));
        assertThat(dto.deletedAt()).isNull();
    }

    @Test
    void subscribedUsersYamlIsDecodedThroughTheSharedParser() {
        Contact contact = new Contact();
        contact.setSubscribedUsers("---\n- 4\n- 12\n");
        assertThat(mapper.toDto(contact).subscribedUsers()).containsExactly(4L, 12L);

        contact.setSubscribedUsers(null);
        assertThat(mapper.toDto(contact).subscribedUsers()).isEmpty();

        contact.setSubscribedUsers("--- !ruby/object:Foo\nbar: 1\n");
        assertThat(mapper.toDto(contact).subscribedUsers()).as("unexpected YAML never fails the mapping").isEmpty();
    }

    @Test
    void nullDoNotCallColumnIsEmittedAsFalseLikeTheRailsBooleanDefault() {
        Contact contact = new Contact();
        contact.setDoNotCall(null);
        assertThat(mapper.toDto(contact).doNotCall()).isFalse();

        contact.setDoNotCall(Boolean.TRUE);
        assertThat(mapper.toDto(contact).doNotCall()).isTrue();
    }

    @Test
    void scalarColumnsMapOneToOneWithoutTrimmingOrDefaulting() {
        Contact contact = new Contact();
        contact.setUserId(1L);
        contact.setLeadId(2L);
        contact.setAssignedTo(3L);
        contact.setReportsTo(4L);
        contact.setFirstName(" Ada ");
        contact.setLastName("");
        contact.setAccess("Shared");
        contact.setEmail("ada@example.com");
        contact.setBornOn(LocalDate.of(1815, 12, 10));
        contact.setBackgroundInfo("Analytical engine.");

        ContactDto dto = mapper.toDto(contact);

        assertThat(dto.userId()).isEqualTo(1);
        assertThat(dto.leadId()).isEqualTo(2);
        assertThat(dto.assignedTo()).isEqualTo(3);
        assertThat(dto.reportsTo()).isEqualTo(4);
        assertThat(dto.firstName()).isEqualTo(" Ada ");
        assertThat(dto.lastName()).isEmpty();
        assertThat(dto.access()).isEqualTo("Shared");
        assertThat(dto.email()).isEqualTo("ada@example.com");
        assertThat(dto.bornOn()).isEqualTo(LocalDate.of(1815, 12, 10));
        assertThat(dto.backgroundInfo()).isEqualTo("Analytical engine.");
        assertThat(dto.title()).isNull();
        assertThat(dto.phone()).isNull();
        assertThat(dto.bluesky()).isNull();
    }
}
