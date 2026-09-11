package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.AbstractIntegrationTest;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.GroupUserRepository;
import com.fatfreecrm.repository.PermissionRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.support.TestDataSeeder.AccountRow;
import com.fatfreecrm.support.TestDataSeeder.ContactRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Every mapped column of the phase-1 entities round-trips from the Rails-shaped schema with the
 * value and type Rails wrote, including the columns the scaffold tests do not touch
 * (timestamps as {@code timestamp without time zone}, {@code numeric(10,6)} coordinates,
 * {@code date} birthdays, opaque YAML {@code subscribed_users}, nullable FK ids).
 */
class EntityColumnMappingTest extends AbstractIntegrationTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2021, 6, 7, 8, 9, 10);
    private static final String SUBSCRIBED_YAML = "---\n- 1\n- 2\n";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private GroupUserRepository groupUserRepository;

    @Test
    void accountMapsEveryColumn() {
        seeder.insertUser(1, "alice", false);
        seeder.insertUser(2, "bob", false);
        seeder.insertAccount(new AccountRow(10, 1L, 2L, "Acme Corp", "Shared", "https://acme.example",
                "1-800-555-0100", "+1 555 0101", "+1 555 0102", "info@acme.example", "Key customer", 3,
                "Customer", SUBSCRIBED_YAML, 4, 5, "Q42", new BigDecimal("37.774929"), new BigDecimal("-122.419416"),
                "https://blog.acme.example", "acme-linkedin", "acme-facebook", "acme-twitter", "acme.bsky.social",
                "acme-instagram", "@acme@mastodon.social", null, CREATED, UPDATED));

        assertThat(accountRepository.findById(10L)).hasValueSatisfying(a -> {
            assertThat(a.getId()).isEqualTo(10L);
            assertThat(a.getUserId()).isEqualTo(1L);
            assertThat(a.getAssignedTo()).isEqualTo(2L);
            assertThat(a.getName()).isEqualTo("Acme Corp");
            assertThat(a.getAccess()).isEqualTo("Shared");
            assertThat(a.getWebsite()).isEqualTo("https://acme.example");
            assertThat(a.getTollFreePhone()).isEqualTo("1-800-555-0100");
            assertThat(a.getPhone()).isEqualTo("+1 555 0101");
            assertThat(a.getFax()).isEqualTo("+1 555 0102");
            assertThat(a.getEmail()).isEqualTo("info@acme.example");
            assertThat(a.getBackgroundInfo()).isEqualTo("Key customer");
            assertThat(a.getRating()).isEqualTo(3);
            assertThat(a.getCategory()).isEqualTo("Customer");
            assertThat(a.getSubscribedUsers()).isEqualTo(SUBSCRIBED_YAML);
            assertThat(a.getContactsCount()).isEqualTo(4);
            assertThat(a.getOpportunitiesCount()).isEqualTo(5);
            assertThat(a.getWikidataId()).isEqualTo("Q42");
            assertThat(a.getLatitude()).isEqualByComparingTo("37.774929");
            assertThat(a.getLongitude()).isEqualByComparingTo("-122.419416");
            assertThat(a.getBlog()).isEqualTo("https://blog.acme.example");
            assertThat(a.getLinkedin()).isEqualTo("acme-linkedin");
            assertThat(a.getFacebook()).isEqualTo("acme-facebook");
            assertThat(a.getTwitter()).isEqualTo("acme-twitter");
            assertThat(a.getBluesky()).isEqualTo("acme.bsky.social");
            assertThat(a.getInstagram()).isEqualTo("acme-instagram");
            assertThat(a.getMastodon()).isEqualTo("@acme@mastodon.social");
            assertThat(a.getDeletedAt()).isNull();
            assertThat(a.getCreatedAt()).isEqualTo(CREATED);
            assertThat(a.getUpdatedAt()).isEqualTo(UPDATED);
        });
    }

    @Test
    void accountNullableColumnsStayNullAndCountersKeepRailsDefaults() {
        seeder.insertUser(1, "alice", false);
        seeder.jdbc().update("INSERT INTO accounts (id, user_id, name) VALUES (20, 1, 'defaults-only')");

        assertThat(accountRepository.findById(20L)).hasValueSatisfying(a -> {
            assertThat(a.getAccess()).isEqualTo("Public");
            assertThat(a.getRating()).isZero();
            assertThat(a.getContactsCount()).isZero();
            assertThat(a.getOpportunitiesCount()).isZero();
            assertThat(a.getAssignedTo()).isNull();
            assertThat(a.getLatitude()).isNull();
            assertThat(a.getLongitude()).isNull();
            assertThat(a.getSubscribedUsers()).isNull();
            assertThat(a.getCreatedAt()).isNull();
            assertThat(a.getUpdatedAt()).isNull();
        });
    }

    @Test
    void contactMapsEveryColumn() {
        seeder.insertUser(1, "alice", false);
        seeder.insertUser(2, "bob", false);
        seeder.insertContact(new ContactRow(30, 1L, 77L, 2L, 31L, "Ada", "Lovelace", "Private", "CTO",
                "Engineering", "Web", "ada@example.com", "ada.alt@example.com", "+44 20 0000", "+44 7000 0000",
                "+44 20 0001", "https://ada.example", "ada-linkedin", "ada-facebook", "ada-twitter",
                LocalDate.of(1815, 12, 10), true, "Pioneer", SUBSCRIBED_YAML, "ada-zoom", "ada-teams", "ada-signal",
                "ada-instagram", "@ada@mastodon.social", "ada.bsky.social", null, CREATED, UPDATED));

        assertThat(contactRepository.findById(30L)).hasValueSatisfying(c -> {
            assertThat(c.getId()).isEqualTo(30L);
            assertThat(c.getUserId()).isEqualTo(1L);
            assertThat(c.getLeadId()).isEqualTo(77L);
            assertThat(c.getAssignedTo()).isEqualTo(2L);
            assertThat(c.getReportsTo()).isEqualTo(31L);
            assertThat(c.getFirstName()).isEqualTo("Ada");
            assertThat(c.getLastName()).isEqualTo("Lovelace");
            assertThat(c.getAccess()).isEqualTo("Private");
            assertThat(c.getTitle()).isEqualTo("CTO");
            assertThat(c.getDepartment()).isEqualTo("Engineering");
            assertThat(c.getSource()).isEqualTo("Web");
            assertThat(c.getEmail()).isEqualTo("ada@example.com");
            assertThat(c.getAltEmail()).isEqualTo("ada.alt@example.com");
            assertThat(c.getPhone()).isEqualTo("+44 20 0000");
            assertThat(c.getMobile()).isEqualTo("+44 7000 0000");
            assertThat(c.getFax()).isEqualTo("+44 20 0001");
            assertThat(c.getBlog()).isEqualTo("https://ada.example");
            assertThat(c.getLinkedin()).isEqualTo("ada-linkedin");
            assertThat(c.getFacebook()).isEqualTo("ada-facebook");
            assertThat(c.getTwitter()).isEqualTo("ada-twitter");
            assertThat(c.getBornOn()).isEqualTo(LocalDate.of(1815, 12, 10));
            assertThat(c.getDoNotCall()).isTrue();
            assertThat(c.getBackgroundInfo()).isEqualTo("Pioneer");
            assertThat(c.getSubscribedUsers()).isEqualTo(SUBSCRIBED_YAML);
            assertThat(c.getZoom()).isEqualTo("ada-zoom");
            assertThat(c.getTeams()).isEqualTo("ada-teams");
            assertThat(c.getSignal()).isEqualTo("ada-signal");
            assertThat(c.getInstagram()).isEqualTo("ada-instagram");
            assertThat(c.getMastodon()).isEqualTo("@ada@mastodon.social");
            assertThat(c.getBluesky()).isEqualTo("ada.bsky.social");
            assertThat(c.getDeletedAt()).isNull();
            assertThat(c.getCreatedAt()).isEqualTo(CREATED);
            assertThat(c.getUpdatedAt()).isEqualTo(UPDATED);
        });
    }

    @Test
    void contactNullableColumnsStayNullAndDefaultsMatchRails() {
        seeder.insertUser(1, "alice", false);
        seeder.jdbc().update("INSERT INTO contacts (id, user_id, first_name, last_name) VALUES (40, 1, 'Only', 'Defaults')");

        assertThat(contactRepository.findById(40L)).hasValueSatisfying(c -> {
            assertThat(c.getAccess()).isEqualTo("Public");
            assertThat(c.getDoNotCall()).isFalse();
            assertThat(c.getLeadId()).isNull();
            assertThat(c.getReportsTo()).isNull();
            assertThat(c.getAssignedTo()).isNull();
            assertThat(c.getBornOn()).isNull();
            assertThat(c.getSubscribedUsers()).isNull();
        });
    }

    @Test
    void userProjectionMapsIdentityAndStatusColumns() {
        LocalDateTime suspended = LocalDateTime.of(2022, 3, 4, 5, 6, 7);
        seeder.insertUser(1, "alice", true, suspended);
        seeder.insertUser(2, "bob", false);
        seeder.softDeleteUser(2);

        assertThat(userRepository.findById(1L)).hasValueSatisfying(u -> {
            assertThat(u.getUsername()).isEqualTo("alice");
            assertThat(u.getEmail()).isEqualTo("alice@example.com");
            assertThat(u.isAdmin()).isTrue();
            assertThat(u.getSuspendedAt()).isEqualTo(suspended);
            assertThat(u.getDeletedAt()).isNull();
        });
        assertThat(userRepository.findById(2L)).isEmpty();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void permissionMapsPolymorphicAssetAndGrantee() {
        long userGrant = seeder.insertPermission("Account", 10, 1L, null);
        long groupGrant = seeder.insertPermission("Contact", 20, null, 100L);

        assertThat(permissionRepository.findById(userGrant)).hasValueSatisfying(p -> {
            assertThat(p.getAssetType()).isEqualTo("Account");
            assertThat(p.getAssetId()).isEqualTo(10L);
            assertThat(p.getUserId()).isEqualTo(1L);
            assertThat(p.getGroupId()).isNull();
            assertThat(p.getCreatedAt()).isNotNull();
            assertThat(p.getUpdatedAt()).isNotNull();
        });
        assertThat(permissionRepository.findById(groupGrant)).hasValueSatisfying(p -> {
            assertThat(p.getAssetType()).isEqualTo("Contact");
            assertThat(p.getAssetId()).isEqualTo(20L);
            assertThat(p.getUserId()).isNull();
            assertThat(p.getGroupId()).isEqualTo(100L);
        });
        assertThat(permissionRepository.count()).isEqualTo(2);
    }

    @Test
    void groupUserCompositeKeyReadsTheJoinTable() {
        seeder.insertUser(1, "alice", false);
        seeder.insertUser(2, "bob", false);
        seeder.insertGroup(100, "sales");
        seeder.insertGroup(101, "support");
        seeder.addUserToGroup(1, 100);
        seeder.addUserToGroup(1, 101);
        seeder.addUserToGroup(2, 101);

        GroupUser.Key key = new GroupUser.Key();
        key.setGroupId(101L);
        key.setUserId(1L);
        assertThat(groupUserRepository.findById(key)).hasValueSatisfying(gu -> {
            assertThat(gu.getKey().getGroupId()).isEqualTo(101L);
            assertThat(gu.getKey().getUserId()).isEqualTo(1L);
        });

        assertThat(groupUserRepository.findGroupIdsByUserId(1L)).containsExactlyInAnyOrder(100L, 101L);
        assertThat(groupUserRepository.findGroupIdsByUserId(2L)).containsExactly(101L);
        assertThat(groupUserRepository.findGroupIdsByUserId(3L)).isEmpty();
        assertThat(groupUserRepository.count()).isEqualTo(3);
    }
}
