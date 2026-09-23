package com.fatfreecrm.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AccountMapper} in isolation (the MapStruct-generated implementation, no Spring
 * context): every entity column lands on the like-named DTO field, timestamps are re-zoned as
 * UTC rather than shifted, and the YAML column is decoded.
 */
class AccountMapperTest {

    private final AccountMapper mapper = new AccountMapperImpl();

    @Test
    void mapsEveryColumnOneToOne() {
        Account account = new Account();
        account.setId(10L);
        account.setUserId(1L);
        account.setAssignedTo(2L);
        account.setName("Acme");
        account.setAccess("Shared");
        account.setWebsite("https://acme.example");
        account.setTollFreePhone("1-800-ACME");
        account.setPhone("555-0100");
        account.setFax("555-0101");
        account.setEmail("sales@acme.example");
        account.setBackgroundInfo("Biggest customer");
        account.setRating(4);
        account.setCategory("Customer");
        account.setSubscribedUsers("---\n- 1\n- 2\n");
        account.setContactsCount(3);
        account.setOpportunitiesCount(2);
        account.setWikidataId("Q42");
        account.setLatitude(new BigDecimal("51.500000"));
        account.setLongitude(new BigDecimal("-0.120000"));
        account.setBlog("https://blog.acme.example");
        account.setLinkedin("acme");
        account.setFacebook("acme.inc");
        account.setTwitter("acme");
        account.setBluesky("acme.bsky.social");
        account.setInstagram("acme_inc");
        account.setMastodon("@acme@mastodon.example");
        account.setDeletedAt(LocalDateTime.of(2024, 3, 3, 3, 0));
        account.setCreatedAt(LocalDateTime.of(2024, 1, 1, 10, 0));
        account.setUpdatedAt(LocalDateTime.of(2024, 1, 2, 10, 0));

        AccountDto dto = mapper.toDto(account);

        assertThat(dto).isEqualTo(new AccountDto(
                10L, 1L, 2L, "Acme", "Shared", "https://acme.example", "1-800-ACME", "555-0100", "555-0101",
                "sales@acme.example", "Biggest customer", 4, "Customer", List.of(1L, 2L), 3, 2, "Q42",
                new BigDecimal("51.500000"), new BigDecimal("-0.120000"), "https://blog.acme.example", "acme",
                "acme.inc", "acme", "acme.bsky.social", "acme_inc", "@acme@mastodon.example",
                OffsetDateTime.of(2024, 3, 3, 3, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2024, 1, 2, 10, 0, 0, 0, ZoneOffset.UTC)));
    }

    @Test
    void timestampsAreTaggedUtcWithoutShiftingTheWallClock() {
        LocalDateTime stored = LocalDateTime.of(2024, 7, 1, 23, 59, 59, 123_456_000);

        OffsetDateTime converted = mapper.utc(stored);

        assertThat(converted.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(converted.toLocalDateTime()).isEqualTo(stored);
        assertThat(converted.getNano()).isEqualTo(123_456_000);
    }

    @Test
    void nullColumnsStayNullAndSubscribedUsersBecomesEmptyList() {
        Account account = new Account();
        account.setId(11L);
        account.setName("Alpha");
        account.setRating(0);

        AccountDto dto = mapper.toDto(account);

        assertThat(dto.id()).isEqualTo(11L);
        assertThat(dto.name()).isEqualTo("Alpha");
        assertThat(dto.rating()).isZero();
        assertThat(dto.userId()).isNull();
        assertThat(dto.assignedTo()).isNull();
        assertThat(dto.access()).isNull();
        assertThat(dto.email()).isNull();
        assertThat(dto.latitude()).isNull();
        assertThat(dto.contactsCount()).isNull();
        assertThat(dto.subscribedUsers()).isEmpty();
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.createdAt()).isNull();
        assertThat(dto.updatedAt()).isNull();
        assertThat(mapper.utc(null)).isNull();
    }

    @Test
    void unparseableSubscribedUsersYamlBecomesEmptyListNotAnError() {
        Account account = new Account();
        account.setId(12L);
        account.setName("Bravo");
        account.setRating(0);
        account.setSubscribedUsers("--- !ruby/object:Set\nhash:\n  1: true\n");

        assertThat(mapper.toDto(account).subscribedUsers()).isEmpty();
    }

    @Test
    void toDtosPreservesOrderAndHandlesEmptyAndNullInput() {
        Account first = new Account();
        first.setId(2L);
        first.setName("Second");
        first.setRating(0);
        Account second = new Account();
        second.setId(1L);
        second.setName("First");
        second.setRating(0);

        assertThat(mapper.toDtos(List.of(first, second))).extracting(AccountDto::id).containsExactly(2L, 1L);
        assertThat(mapper.toDtos(List.of())).isEmpty();
        assertThat(mapper.toDtos(null)).isNull();
    }

    @Test
    void nullEntityMapsToNull() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
