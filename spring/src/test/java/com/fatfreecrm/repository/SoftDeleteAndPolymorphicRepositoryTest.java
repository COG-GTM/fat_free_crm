package com.fatfreecrm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.CrmEntity;
import com.fatfreecrm.domain.PolymorphicRef;
import com.fatfreecrm.domain.Task;
import com.fatfreecrm.support.AbstractRailsSeededIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class SoftDeleteAndPolymorphicRepositoryTest extends AbstractRailsSeededIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private TaggingRepository taggingRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private VersionRepository versionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void softDeletedRailsRowsAreInvisibleButStillStored() {
        Long deletedId = jdbcTemplate.queryForObject(
            "SELECT id FROM accounts WHERE name = 'Initech'", Long.class
        );
        assertThat(accountRepository.findById(deletedId)).isEmpty();
        assertThat(accountRepository.findAll()).extracting(Account::getName).doesNotContain("Initech");
        assertThat(accountRepository.count()).isEqualTo(
            jdbcTemplate.queryForObject("SELECT count(*) FROM accounts WHERE deleted_at IS NULL", Long.class)
        );
    }

    @Test
    void softDeleteStampsDeletedAtInsteadOfDeleting() {
        var globex = accountRepository.findByName("Globex").orElseThrow();
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(accountRepository.softDelete(globex.getId(), now)).isEqualTo(1);
        assertThat(accountRepository.softDelete(globex.getId(), now)).isZero();

        assertThat(accountRepository.findById(globex.getId())).isEmpty();
        assertThat(accountRepository.findByName("Globex")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT deleted_at FROM accounts WHERE id = ?", java.sql.Timestamp.class, globex.getId()
        ).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC)).isEqualTo(now);
    }

    @Test
    void polymorphicPairsResolveThroughDiscriminatorColumns() {
        var acme = accountRepository.findByName("Acme Corp").orElseThrow();
        var ref = acme.toRef();
        assertThat(ref).isEqualTo(PolymorphicRef.of("Account", acme.getId()));

        assertThat(taskRepository.findByAsset(ref)).extracting(Task::getName).containsExactly("Call Acme");
        assertThat(commentRepository.findByCommentableOrderByCreatedAtAsc(ref))
            .singleElement().satisfies(comment -> assertThat(comment.getTitle()).isEqualTo("Review"));
        assertThat(emailRepository.findByMediatorOrderBySentAtDesc(ref))
            .singleElement().satisfies(email -> assertThat(email.getSubject()).isEqualTo("Renewal terms"));
        assertThat(addressRepository.findByAddressableAndAddressType(ref, "Business"))
            .get().satisfies(address -> assertThat(address.getCity()).isEqualTo("San Francisco"));
        assertThat(addressRepository.findByAddressable(ref)).hasSize(1);
        assertThat(taggingRepository.findByTaggable(ref))
            .extracting(tagging -> tagging.getTag().getName()).containsExactlyInAnyOrder("vip", "enterprise");
        assertThat(versionRepository.findByItemOrderByCreatedAtDesc(ref))
            .extracting(version -> version.getEvent()).contains("create", "update", "view");

        var shared = accountRepository.findByAccess(CrmEntity.ACCESS_SHARED);
        assertThat(shared).extracting(Account::getName).containsExactly("Ünïcødé & Sons");
        assertThat(permissionRepository.findAll())
            .filteredOn(permission -> permission.getAsset().equals(shared.get(0).toRef()))
            .hasSize(2)
            .anySatisfy(permission -> assertThat(permission.getGroup()).isNotNull()
                .extracting(group -> group.getName()).isEqualTo("Support"))
            .anySatisfy(permission -> assertThat(permission.getUser()).isNotNull()
                .extracting(user -> user.getUsername()).isEqualTo("carol"));
    }

    @Test
    void writingThroughTheModelStaysReadableByRails() {
        var alice = userRepository.findByLogin("alice", Limit.of(1)).get(0);
        var account = new Account();
        account.setUser(alice);
        account.setName("Spring-created");
        account.setAccess(CrmEntity.ACCESS_PUBLIC);
        account.setRating(3);
        account.subscribe(alice.getId());
        accountRepository.saveAndFlush(account);

        var row = jdbcTemplate.queryForMap("SELECT * FROM accounts WHERE id = ?", account.getId());
        assertThat(row.get("subscribed_users")).isEqualTo("---\n- " + alice.getId() + "\n");
        assertThat(row.get("user_id")).isEqualTo(alice.getId().intValue());
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isEqualTo(row.get("created_at"));
        assertThat(row.get("deleted_at")).isNull();
    }
}
