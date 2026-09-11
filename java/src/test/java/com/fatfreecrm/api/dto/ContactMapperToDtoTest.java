package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Contact;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * {@link ContactMapper#toDto} in isolation: every column of the entity lands on the
 * same-named DTO property, {@code null}s survive untouched and the YAML
 * {@code subscribed_users} text is routed through {@link ContactMapper#parseSubscribedUsers}.
 */
class ContactMapperToDtoTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2024, 5, 1, 9, 30, 0);

    private final ContactMapper mapper = Mappers.getMapper(ContactMapper.class);

    @Test
    void copiesEveryColumnOntoTheDto() {
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

        assertThat(dto).isEqualTo(new ContactDto(20L, 4L, 5L, 2L, 10L, "Hank", "Hill", "Shared", "CTO",
                "Engineering", "Referral", "hank@corp.example", "hank@home.example", "+1-555-0200", "+1-555-0201",
                "+1-555-0202", "https://blog.example", "hank-hill", "hank.hill", "@hank", "hank-zoom", "hank-teams",
                "hank-signal", "hank_ig", "@hank@mastodon.example", "hank.bsky.example", LocalDate.of(1970, 2, 3),
                true, "Met at a conference.", List.of(1L, 2L), null, T0, T0.plusHours(1)));
    }

    @Test
    void nullColumnsStayNullAndSubscribedUsersIsNeverNull() {
        Contact contact = new Contact();
        contact.setId(13L);
        contact.setFirstName("Dave");
        contact.setLastName("Davis");
        contact.setAccess("Private");
        contact.setDoNotCall(false);

        ContactDto dto = mapper.toDto(contact);

        assertThat(dto.id()).isEqualTo(13L);
        assertThat(dto.userId()).isNull();
        assertThat(dto.assignedTo()).isNull();
        assertThat(dto.email()).isNull();
        assertThat(dto.bornOn()).isNull();
        assertThat(dto.doNotCall()).isFalse();
        assertThat(dto.subscribedUsers()).isNotNull().isEmpty();
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.createdAt()).isNull();
    }

    @Test
    void unparseableSubscribedUsersYamlBecomesEmptyListInsteadOfFailingTheMapping() {
        Contact contact = new Contact();
        contact.setId(1L);
        contact.setFirstName("Odd");
        contact.setLastName("Row");
        contact.setDoNotCall(false);
        contact.setSubscribedUsers("--- !ruby/object:Set\nhash: {}\n");

        assertThat(mapper.toDto(contact).subscribedUsers()).isEmpty();

        contact.setSubscribedUsers("--- []\n");
        assertThat(mapper.toDto(contact).subscribedUsers()).isEmpty();
    }

    @Test
    void softDeletedTimestampIsCarriedOver() {
        Contact contact = new Contact();
        contact.setId(15L);
        contact.setFirstName("Frank");
        contact.setLastName("Foster");
        contact.setDoNotCall(false);
        contact.setDeletedAt(T0.plusDays(30));

        assertThat(mapper.toDto(contact).deletedAt()).isEqualTo(T0.plusDays(30));
    }
}
