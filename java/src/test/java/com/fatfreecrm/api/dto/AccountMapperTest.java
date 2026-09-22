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
 * {@link AccountMapper} must carry every {@code accounts} column into {@link AccountDto}
 * unchanged, except for the two documented conversions (YAML {@code subscribed_users} → ids,
 * zone-less UTC timestamps → {@code Z} offset).
 */
class AccountMapperTest {

    private final AccountMapper mapper = new AccountMapperImpl();

    @Test
    void copiesEveryColumnAndConvertsTimestampsToUtcOffset() {
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
        account.setDeletedAt(LocalDateTime.of(2024, 3, 3, 3, 0, 0));
        account.setCreatedAt(LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123_456_000));
        account.setUpdatedAt(LocalDateTime.of(2024, 1, 2, 10, 0, 0));

        AccountDto dto = mapper.toDto(account);

        assertThat(dto.id()).isEqualTo(10L);
        assertThat(dto.userId()).isEqualTo(1L);
        assertThat(dto.assignedTo()).isEqualTo(2L);
        assertThat(dto.name()).isEqualTo("Acme");
        assertThat(dto.access()).isEqualTo("Shared");
        assertThat(dto.website()).isEqualTo("https://acme.example");
        assertThat(dto.tollFreePhone()).isEqualTo("1-800-ACME");
        assertThat(dto.phone()).isEqualTo("555-0100");
        assertThat(dto.fax()).isEqualTo("555-0101");
        assertThat(dto.email()).isEqualTo("sales@acme.example");
        assertThat(dto.backgroundInfo()).isEqualTo("Biggest customer");
        assertThat(dto.rating()).isEqualTo(4);
        assertThat(dto.category()).isEqualTo("Customer");
        assertThat(dto.subscribedUsers()).containsExactly(1L, 2L);
        assertThat(dto.contactsCount()).isEqualTo(3);
        assertThat(dto.opportunitiesCount()).isEqualTo(2);
        assertThat(dto.wikidataId()).isEqualTo("Q42");
        assertThat(dto.latitude()).isEqualByComparingTo("51.5");
        assertThat(dto.longitude()).isEqualByComparingTo("-0.12");
        assertThat(dto.blog()).isEqualTo("https://blog.acme.example");
        assertThat(dto.linkedin()).isEqualTo("acme");
        assertThat(dto.facebook()).isEqualTo("acme.inc");
        assertThat(dto.twitter()).isEqualTo("acme");
        assertThat(dto.bluesky()).isEqualTo("acme.bsky.social");
        assertThat(dto.instagram()).isEqualTo("acme_inc");
        assertThat(dto.mastodon()).isEqualTo("@acme@mastodon.example");
        assertThat(dto.deletedAt()).isEqualTo(OffsetDateTime.of(2024, 3, 3, 3, 0, 0, 0, ZoneOffset.UTC));
        assertThat(dto.createdAt()).isEqualTo(OffsetDateTime.of(2024, 1, 1, 10, 0, 0, 123_456_000, ZoneOffset.UTC));
        assertThat(dto.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(dto.updatedAt()).isEqualTo(OffsetDateTime.of(2024, 1, 2, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void nullColumnsStayNullExceptSubscribedUsersWhichBecomesEmptyList() {
        Account account = new Account();
        account.setId(11L);
        account.setName("Alpha");
        account.setAccess("Private");
        account.setRating(0);

        AccountDto dto = mapper.toDto(account);

        assertThat(dto.id()).isEqualTo(11L);
        assertThat(dto.userId()).isNull();
        assertThat(dto.assignedTo()).isNull();
        assertThat(dto.website()).isNull();
        assertThat(dto.email()).isNull();
        assertThat(dto.category()).isNull();
        assertThat(dto.contactsCount()).isNull();
        assertThat(dto.latitude()).isNull();
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.createdAt()).isNull();
        assertThat(dto.updatedAt()).isNull();
        assertThat(dto.subscribedUsers()).isNotNull().isEmpty();
    }

    @Test
    void unparseableSubscribedUsersYamlBecomesEmptyList() {
        Account account = new Account();
        account.setId(12L);
        account.setName("Bravo");
        account.setRating(0);
        account.setSubscribedUsers("--- !ruby/object:Set\nhash:\n  1: true\n");

        assertThat(mapper.toDto(account).subscribedUsers()).isEmpty();
    }

    @Test
    void toDtosPreservesOrderAndHandlesEmptyInput() {
        Account first = new Account();
        first.setId(1L);
        first.setName("First");
        first.setRating(0);
        Account second = new Account();
        second.setId(2L);
        second.setName("Second");
        second.setRating(0);

        assertThat(mapper.toDtos(List.of(second, first))).extracting(AccountDto::id).containsExactly(2L, 1L);
        assertThat(mapper.toDtos(List.of())).isEmpty();
    }
}
