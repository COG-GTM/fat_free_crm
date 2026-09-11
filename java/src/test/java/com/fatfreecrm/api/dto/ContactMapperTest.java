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
 * {@link ContactMapper} must carry every {@link Contact} column into {@link ContactDto} unchanged
 * apart from the two documented conversions (YAML {@code subscribed_users} → list, naive UTC
 * timestamps → {@code OffsetDateTime} at {@code Z}).
 */
class ContactMapperTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2024, 5, 1, 9, 30, 15);

    private final ContactMapper mapper = Mappers.getMapper(ContactMapper.class);

    @Test
    void mapsEveryColumnByName() {
        Contact contact = new Contact();
        contact.setId(20L);
        contact.setUserId(4L);
        contact.setLeadId(5L);
        contact.setAssignedTo(2L);
        contact.setReportsTo(10L);
        contact.setFirstName("Hank");
        contact.setLastName("Hill");
        contact.setAccess("Shared");
        contact.setTitle("CTO");
        contact.setDepartment("Engineering");
        contact.setSource("Referral");
        contact.setEmail("hank@corp.example");
        contact.setAltEmail("hank@home.example");
        contact.setPhone("+1-555-0200");
        contact.setMobile("+1-555-0201");
        contact.setFax("+1-555-0202");
        contact.setBlog("https://blog.example");
        contact.setLinkedin("hank-hill");
        contact.setFacebook("hank.hill");
        contact.setTwitter("@hank");
        contact.setZoom("hank-zoom");
        contact.setTeams("hank-teams");
        contact.setSignal("hank-signal");
        contact.setInstagram("hank_ig");
        contact.setMastodon("@hank@mastodon.example");
        contact.setBluesky("hank.bsky.example");
        contact.setBornOn(LocalDate.of(1970, 2, 3));
        contact.setDoNotCall(true);
        contact.setBackgroundInfo("Met at a conference.");
        contact.setSubscribedUsers("---\n- 1\n- 2\n");
        contact.setDeletedAt(null);
        contact.setCreatedAt(T0);
        contact.setUpdatedAt(T0.plusHours(1));

        ContactDto dto = mapper.toDto(contact);

        assertThat(dto.id()).isEqualTo(20L);
        assertThat(dto.userId()).isEqualTo(4L);
        assertThat(dto.leadId()).isEqualTo(5L);
        assertThat(dto.assignedTo()).isEqualTo(2L);
        assertThat(dto.reportsTo()).isEqualTo(10L);
        assertThat(dto.firstName()).isEqualTo("Hank");
        assertThat(dto.lastName()).isEqualTo("Hill");
        assertThat(dto.access()).isEqualTo("Shared");
        assertThat(dto.title()).isEqualTo("CTO");
        assertThat(dto.department()).isEqualTo("Engineering");
        assertThat(dto.source()).isEqualTo("Referral");
        assertThat(dto.email()).isEqualTo("hank@corp.example");
        assertThat(dto.altEmail()).isEqualTo("hank@home.example");
        assertThat(dto.phone()).isEqualTo("+1-555-0200");
        assertThat(dto.mobile()).isEqualTo("+1-555-0201");
        assertThat(dto.fax()).isEqualTo("+1-555-0202");
        assertThat(dto.blog()).isEqualTo("https://blog.example");
        assertThat(dto.linkedin()).isEqualTo("hank-hill");
        assertThat(dto.facebook()).isEqualTo("hank.hill");
        assertThat(dto.twitter()).isEqualTo("@hank");
        assertThat(dto.zoom()).isEqualTo("hank-zoom");
        assertThat(dto.teams()).isEqualTo("hank-teams");
        assertThat(dto.signal()).isEqualTo("hank-signal");
        assertThat(dto.instagram()).isEqualTo("hank_ig");
        assertThat(dto.mastodon()).isEqualTo("@hank@mastodon.example");
        assertThat(dto.bluesky()).isEqualTo("hank.bsky.example");
        assertThat(dto.bornOn()).isEqualTo(LocalDate.of(1970, 2, 3));
        assertThat(dto.doNotCall()).isTrue();
        assertThat(dto.backgroundInfo()).isEqualTo("Met at a conference.");
        assertThat(dto.subscribedUsers()).containsExactly(1L, 2L);
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.createdAt()).isEqualTo(OffsetDateTime.of(T0, ZoneOffset.UTC));
        assertThat(dto.updatedAt()).isEqualTo(OffsetDateTime.of(T0.plusHours(1), ZoneOffset.UTC));
    }

    @Test
    void sparseRowKeepsNullsAndEmptySubscribedUsers() {
        Contact contact = new Contact();
        contact.setId(13L);
        contact.setUserId(4L);
        contact.setFirstName("Dave");
        contact.setLastName("Davis");
        contact.setAccess("Private");
        contact.setDoNotCall(false);

        ContactDto dto = mapper.toDto(contact);

        assertThat(dto.id()).isEqualTo(13L);
        assertThat(dto.leadId()).isNull();
        assertThat(dto.assignedTo()).isNull();
        assertThat(dto.reportsTo()).isNull();
        assertThat(dto.email()).isNull();
        assertThat(dto.bornOn()).isNull();
        assertThat(dto.doNotCall()).isFalse();
        assertThat(dto.subscribedUsers()).isNotNull().isEmpty();
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.createdAt()).isNull();
        assertThat(dto.updatedAt()).isNull();
    }

    @Test
    void softDeletedTimestampIsExposedAsUtc() {
        Contact contact = new Contact();
        contact.setId(15L);
        contact.setFirstName("Frank");
        contact.setLastName("Foster");
        contact.setDoNotCall(false);
        contact.setDeletedAt(T0.plusDays(30));

        assertThat(mapper.toDto(contact).deletedAt()).isEqualTo(OffsetDateTime.of(T0.plusDays(30), ZoneOffset.UTC));
    }

    @Test
    void utcConversionIsNullSafeAndNeverShiftsTheWallClock() {
        assertThat(mapper.utc(null)).isNull();

        OffsetDateTime converted = mapper.utc(T0);
        assertThat(converted.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(converted.toLocalDateTime()).isEqualTo(T0);
    }

    @Test
    void subscribedUsersDelegatesToTheSharedParser() {
        assertThat(mapper.subscribedUsers(null)).isEmpty();
        assertThat(mapper.subscribedUsers("--- []\n")).isEmpty();
        assertThat(mapper.subscribedUsers("---\n- 7\n")).containsExactly(7L);
        assertThat(mapper.subscribedUsers("---\n- alice\n")).isEmpty();
    }

    @Test
    void nullEntityMapsToNull() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
