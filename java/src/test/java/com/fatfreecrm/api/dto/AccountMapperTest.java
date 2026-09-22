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
 * {@link AccountMapper} in isolation (no Spring context): every column of the Rails
 * {@code accounts} table lands on the DTO field of the same name, the two custom conversions
 * behave, and sparse rows produce {@code null}s rather than defaults.
 */
class AccountMapperTest {

    private final AccountMapper mapper = new AccountMapperImpl();

    @Test
    void mapsEveryColumnOneToOne() {
        Account account = fullAccount();

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
        assertThat(dto.latitude()).isEqualByComparingTo("51.500000");
        assertThat(dto.longitude()).isEqualByComparingTo("-0.120000");
        assertThat(dto.blog()).isEqualTo("https://blog.acme.example");
        assertThat(dto.linkedin()).isEqualTo("acme");
        assertThat(dto.facebook()).isEqualTo("acme.inc");
        assertThat(dto.twitter()).isEqualTo("acme");
        assertThat(dto.bluesky()).isEqualTo("acme.bsky.social");
        assertThat(dto.instagram()).isEqualTo("acme_inc");
        assertThat(dto.mastodon()).isEqualTo("@acme@mastodon.example");
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.createdAt()).isEqualTo(OffsetDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC));
        assertThat(dto.updatedAt()).isEqualTo(OffsetDateTime.of(2024, 1, 2, 10, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void railsTimestampsAreReattachedToUtcNotTheJvmZone() {
        LocalDateTime stored = LocalDateTime.of(2024, 3, 31, 1, 30, 0, 123_456_000);

        OffsetDateTime mapped = mapper.utc(stored);

        assertThat(mapped.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(mapped.toLocalDateTime()).isEqualTo(stored);
        assertThat(mapper.utc(null)).isNull();
    }

    @Test
    void deletedAtIsMappedWhenPresent() {
        Account account = fullAccount();
        account.setDeletedAt(LocalDateTime.of(2025, 5, 5, 5, 5, 5));

        assertThat(mapper.toDto(account).deletedAt())
                .isEqualTo(OffsetDateTime.of(2025, 5, 5, 5, 5, 5, 0, ZoneOffset.UTC));
    }

    @Test
    void subscribedUsersColumnIsDecodedThroughTheYamlParser() {
        Account account = fullAccount();

        account.setSubscribedUsers("---\n- 7\n- 9\n");
        assertThat(mapper.toDto(account).subscribedUsers()).containsExactly(7L, 9L);

        account.setSubscribedUsers("--- []\n");
        assertThat(mapper.toDto(account).subscribedUsers()).isEmpty();

        account.setSubscribedUsers("--- !ruby/object:Foo\nbar: 1\n");
        assertThat(mapper.toDto(account).subscribedUsers()).isEmpty();

        account.setSubscribedUsers(null);
        assertThat(mapper.toDto(account).subscribedUsers()).isNotNull().isEmpty();
    }

    @Test
    void sparseRowProducesNullsAndEmptyList() {
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
        assertThat(dto.opportunitiesCount()).isNull();
        assertThat(dto.latitude()).isNull();
        assertThat(dto.longitude()).isNull();
        assertThat(dto.subscribedUsers()).isEmpty();
        assertThat(dto.deletedAt()).isNull();
        assertThat(dto.createdAt()).isNull();
        assertThat(dto.updatedAt()).isNull();
    }

    @Test
    void nullEntityMapsToNull() {
        assertThat(mapper.toDto(null)).isNull();
        assertThat(mapper.toDtos(null)).isNull();
    }

    @Test
    void toDtosPreservesOrderAndSize() {
        Account first = fullAccount();
        Account second = fullAccount();
        second.setId(20L);
        second.setName("Zulu");

        List<AccountDto> dtos = mapper.toDtos(List.of(first, second));

        assertThat(dtos).extracting(AccountDto::id).containsExactly(10L, 20L);
        assertThat(dtos).extracting(AccountDto::name).containsExactly("Acme", "Zulu");
        assertThat(mapper.toDtos(List.of())).isEmpty();
    }

    private static Account fullAccount() {
        Account a = new Account();
        a.setId(10L);
        a.setUserId(1L);
        a.setAssignedTo(2L);
        a.setName("Acme");
        a.setAccess("Shared");
        a.setWebsite("https://acme.example");
        a.setTollFreePhone("1-800-ACME");
        a.setPhone("555-0100");
        a.setFax("555-0101");
        a.setEmail("sales@acme.example");
        a.setBackgroundInfo("Biggest customer");
        a.setRating(4);
        a.setCategory("Customer");
        a.setSubscribedUsers("---\n- 1\n- 2\n");
        a.setContactsCount(3);
        a.setOpportunitiesCount(2);
        a.setWikidataId("Q42");
        a.setLatitude(new BigDecimal("51.500000"));
        a.setLongitude(new BigDecimal("-0.120000"));
        a.setBlog("https://blog.acme.example");
        a.setLinkedin("acme");
        a.setFacebook("acme.inc");
        a.setTwitter("acme");
        a.setBluesky("acme.bsky.social");
        a.setInstagram("acme_inc");
        a.setMastodon("@acme@mastodon.example");
        a.setCreatedAt(LocalDateTime.of(2024, 1, 1, 10, 0, 0));
        a.setUpdatedAt(LocalDateTime.of(2024, 1, 2, 10, 0, 0));
        return a;
    }
}
