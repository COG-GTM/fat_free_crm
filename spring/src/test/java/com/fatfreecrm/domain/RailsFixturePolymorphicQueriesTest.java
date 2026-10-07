package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.ActivityRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.fatfreecrm.repository.AvatarRepository;
import com.fatfreecrm.repository.CommentRepository;
import com.fatfreecrm.repository.EmailRepository;
import com.fatfreecrm.repository.FieldRepository;
import com.fatfreecrm.repository.PermissionRepository;
import com.fatfreecrm.repository.PreferenceRepository;
import com.fatfreecrm.repository.SavedListRepository;
import com.fatfreecrm.repository.SettingRepository;
import com.fatfreecrm.repository.TaggingRepository;
import com.fatfreecrm.repository.TaskRepository;
import com.fatfreecrm.repository.VersionRepository;
import com.fatfreecrm.service.PolymorphicReferenceService;
import com.fatfreecrm.support.RailsEntityFixturePostgres;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs the Spring repository finders and {@link PolymorphicReferenceService} against rows that
 * Rails itself created (lib/tasks/ffcrm/entity_fixture.rake) and checks they return exactly the
 * rows Rails' polymorphic associations ({@code has_many :tasks, as: :asset}, {@code has_paper_trail
 * meta: {related: ...}}, acts-as-taggable, permissions, ...) attached to each record.
 */
@SpringBootTest
class RailsFixturePolymorphicQueriesTest {

    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = RailsEntityFixturePostgres.started();

    private static final String SHARED_ACCOUNT = "Entity Fixture Account";

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private AvatarRepository avatarRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private FieldRepository fieldRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private PreferenceRepository preferenceRepository;

    @Autowired
    private SavedListRepository savedListRepository;

    @Autowired
    private SettingRepository settingRepository;

    @Autowired
    private TaggingRepository taggingRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private VersionRepository versionRepository;

    @Autowired
    private PolymorphicReferenceService polymorphicReferenceService;

    @Test
    @Transactional
    void parsesTheYamlAndBase64ColumnsRailsSerializeWrote() throws IOException {
        Map<String, Setting> settings = byName(settingRepository.findAll(), Setting::getName);
        assertThat(settings.get("fixture_string").getParsedValue()).isEqualTo("string value");
        assertThat(settings.get("fixture_symbols").getParsedValue()).isEqualTo(List.of(":one", ":two"));
        assertThat(settings.get("fixture_hash").getParsedValue()).isEqualTo(Map.of("nested", List.of("value", 2)));

        Map<String, Field> fields = byName(fieldRepository.findAll(), Field::getName);
        Field core = fields.get("fixture_core");
        assertThat(core.getType()).isEqualTo("CoreField");
        assertThat(core.getAsValue()).isEqualTo("string");
        assertThat(core.getCollection()).isNull();
        assertThat(core.getCollectionValues()).isEmpty();
        assertThat(core.getSettingsMap()).isEmpty();
        assertThat(core.getMinlength()).isZero();

        Field custom = fields.get("fixture_custom");
        assertThat(custom.getType()).isEqualTo("CustomField");
        assertThat(custom.getAsValue()).isEqualTo("check_boxes");
        assertThat(custom.getCollectionValues()).containsExactly("one", "two");
        assertThat(custom.getSettingsMap()).containsExactly(Map.entry("multiple", true));
        assertThat(custom.getFieldGroup().getName()).isEqualTo("Fixture Fields");
        assertThat(custom.getFieldGroup().getKlassName()).isEqualTo("Account");
        assertThat(custom.getFieldGroup().getTag().getName()).isEqualTo("entity-fixture");
        assertThat(custom.getFieldGroup()).isSameAs(core.getFieldGroup());

        Map<String, Preference> preferences = byName(preferenceRepository.findAll(), Preference::getName);
        JsonNode goldens;
        try (InputStream input = getClass().getResourceAsStream("/db/rails/serialized_formats.json")) {
            goldens = new ObjectMapper().readTree(input).get("preferences");
        }
        assertThat(goldens.size()).isPositive();
        for (JsonNode golden : goldens) {
            Preference preference = preferences.get(golden.get("name").textValue());
            assertThat(preference).as(golden.get("name").textValue()).isNotNull();
            assertThat(preference.getValue()).isEqualTo(golden.get("serialized").textValue());
            assertThat(preference.getJsonValue()).isEqualTo(golden.get("json").textValue());
            assertThat(preference.getUser().getUsername()).isEqualTo("entity_owner");
        }
    }

    @Test
    @Transactional
    void polymorphicFindersReturnTheRowsRailsAttachedToTheSharedAccount() {
        Account shared = accountByName(SHARED_ACCOUNT);
        int id = shared.getId().intValue();

        assertThat(taggingRepository.findByTaggableTypeAndTaggableId(RailsModelType.ACCOUNT, id))
            .extracting(
                tagging -> tagging.getTag().getName(),
                Tagging::getContext,
                tagging -> tagging.taggerModelType().isPresent()
            )
            .containsExactlyInAnyOrder(
                Tuple.tuple("entity-fixture", "tags", false),
                Tuple.tuple("domain-model", "tags", false)
            );
        assertThat(taggingRepository.findByTaggerTypeAndTaggerId(RailsModelType.USER, 1)).isEmpty();

        List<Permission> permissions = permissionRepository.findByAssetTypeAndAssetId(RailsModelType.ACCOUNT, id);
        assertThat(permissions).hasSize(2);
        assertThat(permissions).filteredOn(permission -> permission.getGroup() == null)
            .singleElement()
            .satisfies(permission -> assertThat(permission.getUser().getUsername()).isEqualTo("entity_assignee"));
        assertThat(permissions).filteredOn(permission -> permission.getUser() == null)
            .singleElement()
            .satisfies(permission -> assertThat(permission.getGroup().getName()).isEqualTo("Entity Fixture Group"));
        assertThat(permissions)
            .allSatisfy(permission -> assertThat(permission.assetModelType()).contains(RailsModelType.ACCOUNT));

        assertThat(taskRepository.findByAssetTypeAndAssetId(RailsModelType.ACCOUNT, id))
            .extracting(Task::getName).containsExactly("Task for Account");
        assertThat(commentRepository.findByCommentableTypeAndCommentableId(RailsModelType.ACCOUNT, id))
            .extracting(Comment::getComment, Comment::getTitle, Comment::getState)
            .containsExactly(Tuple.tuple("Fixture comment", "", "Expanded"));
        assertThat(emailRepository.findByMediatorTypeAndMediatorId(RailsModelType.ACCOUNT, id))
            .extracting(Email::getImapMessageId).containsExactly("fixture-account@example.test");
        assertThat(addressRepository.findByAddressableTypeAndAddressableId(RailsModelType.ACCOUNT, id))
            .extracting(Address::getAddressType, address -> address.getDeletedAt() != null)
            .containsExactlyInAnyOrder(
                Tuple.tuple("Business", true),
                Tuple.tuple("Billing", false),
                Tuple.tuple("Shipping", false)
            );
        assertThat(activityRepository.findBySubjectTypeAndSubjectId(RailsModelType.ACCOUNT, id))
            .extracting(Activity::getInfo, Activity::getAction, Activity::getPrivate)
            .containsExactly(Tuple.tuple("Fixture activity", "created", false));
        assertThat(avatarRepository.findByEntityTypeAndEntityId(RailsModelType.USER, 1))
            .singleElement().satisfies(avatar -> assertThat(avatar.getUser().getUsername()).isEqualTo("entity_owner"));
        assertThat(avatarRepository.findByEntityTypeAndEntityId(RailsModelType.CONTACT, 1)).hasSize(1);
        assertThat(avatarRepository.findByEntityTypeAndEntityId(RailsModelType.ACCOUNT, id)).isEmpty();
    }

    @Test
    @Transactional
    void paperTrailVersionsAreReachableByItemAndByRelatedRecord() {
        int id = accountByName(SHARED_ACCOUNT).getId().intValue();

        List<Version> own = versionRepository.findByItemTypeAndItemId(RailsModelType.ACCOUNT, id).stream()
            .sorted(Comparator.comparing(Version::getId))
            .toList();
        assertThat(own).extracting(Version::getEvent).containsExactly("create", "update", "update");
        assertThat(own).extracting(Version::getWhodunnit).containsOnly("1");
        assertThat(own).allSatisfy(version -> {
            assertThat(version.itemModelType()).contains(RailsModelType.ACCOUNT);
            assertThat(version.getRelatedType()).isNull();
            assertThat(version.getRelatedId()).isNull();
            assertThat(version.getCreatedAt()).isNotNull();
        });
        assertThat(own.get(0).getObject()).isNull();
        assertThat(own.get(1).getObject()).contains("name: " + SHARED_ACCOUNT);

        Map<String, Long> relatedByItemType = versionRepository
            .findByRelatedTypeAndRelatedId(RailsModelType.ACCOUNT, id).stream()
            .collect(Collectors.groupingBy(Version::getItemType, Collectors.counting()));
        assertThat(relatedByItemType).containsOnly(
            Map.entry("Task", 1L), Map.entry("Comment", 1L), Map.entry("Email", 1L), Map.entry("Address", 3L)
        );
        assertThat(versionRepository.findByRelatedTypeAndRelatedId(RailsModelType.ACCOUNT, id))
            .extracting(Version::getEvent).containsOnly("create");
    }

    @Test
    @Transactional
    void findersIsolateAssetsAndTreatNullReferencesLikeRailsWhereNil() {
        int sharedId = accountByName(SHARED_ACCOUNT).getId().intValue();
        int leadAccessId = accountByName("Lead access fixture").getId().intValue();

        assertThat(permissionRepository.findByAssetTypeAndAssetId(RailsModelType.ACCOUNT, leadAccessId)).isEmpty();
        assertThat(taskRepository.findByAssetTypeAndAssetId(RailsModelType.CONTACT, sharedId)).isEmpty();
        assertThat(taskRepository.findByAssetTypeAndAssetId(RailsModelType.CONTACT, 1))
            .extracting(Task::getName)
            .containsExactlyInAnyOrder("Task for Contact", "Completed fixture task");
        assertThat(taskRepository.findByAssetTypeAndAssetId("account", sharedId)).isEmpty();
        assertThat(taskRepository.findByAssetTypeAndAssetId("Nonexistent", sharedId)).isEmpty();
        assertThat(taskRepository.findByAssetTypeAndAssetId(RailsModelType.ACCOUNT, 999_999)).isEmpty();

        assertThat(taskRepository.findByAssetTypeAndAssetId((String) null, null))
            .extracting(Task::getName)
            .containsExactlyInAnyOrder("Standalone fixture task", "Recreated standalone fixture task");
        assertThat(taskRepository.findByAssetTypeAndAssetId((String) null, sharedId)).isEmpty();
    }

    @Test
    @Transactional
    void resolvesEveryRailsPolymorphicTargetInTheFixture() {
        Account shared = accountByName(SHARED_ACCOUNT);
        Task task = taskRepository.findByAssetTypeAndAssetId(RailsModelType.ACCOUNT, shared.getId().intValue()).get(0);

        assertThat(polymorphicReferenceService.resolve(task.assetModelType().orElseThrow(), task.getAssetId()))
            .containsSame(shared);
        assertThat(polymorphicReferenceService.resolve(task.getAssetType(), task.getAssetId()))
            .containsSame(shared);

        Permission groupPermission = permissionRepository
            .findByAssetTypeAndAssetId(RailsModelType.ACCOUNT, shared.getId().intValue()).get(0);
        assertThat(polymorphicReferenceService.resolve(groupPermission.getAssetType(), groupPermission.getAssetId()))
            .containsSame(shared);

        Version userVersion = versionRepository.findByItemTypeAndItemId(RailsModelType.USER, 1).get(0);
        RailsModelType userType = userVersion.itemModelType().orElseThrow();
        assertThat(polymorphicReferenceService.resolve(userType, userVersion.getItemId()))
            .get().isInstanceOfSatisfying(User.class, user -> assertThat(user.getUsername()).isEqualTo("entity_owner"));

        SavedList savedList = savedListRepository.findAll().get(0);
        assertThat(polymorphicReferenceService.resolve("List", savedList.getId().intValue()))
            .containsSame(savedList);

        assertThat(polymorphicReferenceService.resolve(RailsModelType.ACCOUNT, 999_999)).isEmpty();
        assertThat(polymorphicReferenceService.resolve(RailsModelType.ACCOUNT, null)).isEmpty();
        assertThat(polymorphicReferenceService.resolve((RailsModelType) null, shared.getId().intValue())).isEmpty();
        assertThat(polymorphicReferenceService.resolve("SavedList", savedList.getId().intValue())).isEmpty();
        assertThat(polymorphicReferenceService.resolve("account", shared.getId().intValue())).isEmpty();
    }

    private Account accountByName(String name) {
        return accountRepository.findAll().stream()
            .filter(account -> name.equals(account.getName()))
            .findFirst()
            .orElseThrow();
    }

    private static <T> Map<String, T> byName(List<T> rows, Function<T, String> name) {
        return rows.stream().collect(Collectors.toMap(name, Function.identity()));
    }
}
