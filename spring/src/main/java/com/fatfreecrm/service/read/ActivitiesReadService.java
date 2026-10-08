package com.fatfreecrm.service.read;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.Version;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.repository.ActivityVisibilityRepository;
import com.fatfreecrm.repository.UserRepository;
import com.fatfreecrm.repository.VersionRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.PolymorphicReferenceService;
import com.fatfreecrm.service.json.RailsJsonWriter;
import com.fatfreecrm.service.json.RailsResources;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class ActivitiesReadService {

    private static final Map<String, String> ACCESS_TABLES = Map.of(
        "Account", "accounts",
        "Campaign", "campaigns",
        "Contact", "contacts",
        "Lead", "leads",
        "Opportunity", "opportunities"
    );
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z_]\\w*):");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|token|salt).*");
    private static final Pattern DURATION = Pattern.compile("^(one|two)_(hour|day|days|week|weeks|month)$");

    private final VersionRepository versionRepository;
    private final UserRepository userRepository;
    private final UserPreferenceService preferenceService;
    private final PolymorphicReferenceService polymorphicReferences;
    private final RailsJsonWriter jsonWriter;
    private final RailsResources resources;
    private final ActivityVisibilityRepository visibilityRepository;

    public ActivitiesReadService(
        VersionRepository versionRepository,
        UserRepository userRepository,
        UserPreferenceService preferenceService,
        PolymorphicReferenceService polymorphicReferences,
        RailsJsonWriter jsonWriter,
        RailsResources resources,
        ActivityVisibilityRepository visibilityRepository
    ) {
        this.versionRepository = versionRepository;
        this.userRepository = userRepository;
        this.preferenceService = preferenceService;
        this.polymorphicReferences = polymorphicReferences;
        this.jsonWriter = jsonWriter;
        this.resources = resources;
        this.visibilityRepository = visibilityRepository;
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> list(AuthenticatedUser user, MultiValueMap<String, String> parameters) {
        String asset = value(user, parameters, "asset", "activity_asset");
        String event = value(user, parameters, "event", "activity_event");
        String actor = value(user, parameters, "user", "activity_user");
        String duration = value(user, parameters, "duration", "activity_duration");

        Specification<Version> specification = (root, query, builder) -> builder.greaterThanOrEqualTo(
            root.get("createdAt"), cutoff(duration));
        if (asset != null && !asset.equals("all")) {
            String itemType = singularize(asset);
            specification = specification.and((root, query, builder) -> builder.equal(root.get("itemType"), itemType));
        }
        if (event != null) {
            if (event.equals("all_events")) {
                specification = specification.and((root, query, builder) ->
                    root.get("event").in("create", "update", "destroy"));
            } else {
                specification = specification.and((root, query, builder) -> builder.equal(root.get("event"), event));
            }
        }
        Long actorId = actorId(actor);
        if (actorId != null) {
            String whodunnit = actorId.toString();
            specification = specification.and((root, query, builder) ->
                builder.equal(root.get("whodunnit"), whodunnit));
        }
        List<Version> latest = versionRepository.findAll(
            specification,
            PageRequest.of(0, 500, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        ).getContent();
        List<Long> visibleIds = latest.stream()
            .filter(version -> visible(version, user.id()))
            .map(Version::getId)
            .toList();
        List<ObjectNode> result = jsonWriter.write(resources.version, visibleIds);
        result.forEach(this::scrubSecrets);
        return result;
    }

    private String value(
        AuthenticatedUser user,
        MultiValueMap<String, String> parameters,
        String parameter,
        String pref
    ) {
        return parameters.containsKey(parameter)
            ? parameters.getFirst(parameter)
            : preferenceService.stringPreference(user.id(), pref).orElse(null);
    }

    private Long actorId(String actor) {
        if (actor == null || actor.equals("all_users")) {
            return null;
        }
        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        if (actor.contains("@")) {
            return users.stream()
                .filter(user -> actor.equals(user.getEmail()))
                .map(User::getId)
                .findFirst()
                .orElse(null);
        }
        List<String[]> names = new ArrayList<>();
        if (actor.contains(" ")) {
            String[] parts = actor.strip().split("\\s+");
            for (int index = 1; index < parts.length; index++) {
                String first = String.join(" ", java.util.Arrays.copyOfRange(parts, 0, index));
                String last = String.join(" ", java.util.Arrays.copyOfRange(parts, index, parts.length));
                names.add(new String[] {first, last});
                names.add(new String[] {last, first});
            }
            for (String[] name : names) {
                Long match = users.stream()
                    .filter(candidate ->
                        name[0].equals(candidate.getFirstName()) && name[1].equals(candidate.getLastName()))
                    .map(User::getId)
                    .findFirst()
                    .orElse(null);
                if (match != null) {
                    return match;
                }
            }
        } else {
            return users.stream()
                .filter(candidate -> actor.equals(candidate.getFirstName()) || actor.equals(candidate.getLastName()))
                .map(User::getId).findFirst().orElse(null);
        }
        return null;
    }

    private static Instant cutoff(String raw) {
        String value = raw == null ? "two_days" : raw;
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            return Instant.now().minus(Duration.ofDays(2));
        }
        int number = matcher.group(1).equals("one") ? 1 : 2;
        String unit = matcher.group(2);
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        return switch (unit) {
            case "hour" -> now.minusHours(number).toInstant();
            case "day", "days" -> now.minusDays(number).toInstant();
            case "week", "weeks" -> now.minusWeeks(number).toInstant();
            case "month" -> now.minusMonths(number).toInstant();
            default -> now.minusDays(2).toInstant();
        };
    }

    private static String singularize(String value) {
        String singular = value.endsWith("ies")
            ? value.substring(0, value.length() - 3) + "y"
            : value.endsWith("s") && !value.endsWith("ss")
                ? value.substring(0, value.length() - 1)
                : value;
        return singular.isEmpty() ? singular : singular.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
            + singular.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private boolean visible(Version version, long userId) {
        if (version.getItemType() == null || version.getItemId() == null) {
            return false;
        }
        RailsModelType type = RailsModelType.fromRailsName(version.getItemType()).orElse(null);
        if (type == null) {
            return false;
        }
        boolean live = polymorphicReferences.resolve(type, version.getItemId()).isPresent();
        if (!live && version.getObject() == null) {
            return false;
        }
        String table = ACCESS_TABLES.get(type.railsName());
        if (table == null) {
            return true;
        }
        Object owner;
        Object assigned;
        Object access;
        if (live) {
            Map<String, Object> fields = visibilityRepository.findAccessFields(table, version.getItemId())
                .orElse(Map.of());
            owner = fields.get("user_id");
            assigned = fields.get("assigned_to");
            access = fields.get("access");
            if (owner == ActivityVisibilityRepository.NullValue.INSTANCE) {
                owner = null;
            }
            if (assigned == ActivityVisibilityRepository.NullValue.INSTANCE) {
                assigned = null;
            }
            if (access == ActivityVisibilityRepository.NullValue.INSTANCE) {
                access = null;
            }
        } else {
            Map<String, Object> object;
            try {
                object = RailsYaml.readStringMap(version.getObject());
            } catch (RuntimeException ignored) {
                return false;
            }
            owner = object.get("user_id");
            assigned = object.get("assigned_to");
            access = object.get("access");
        }
        if (sameId(owner, userId) || sameId(assigned, userId)) {
            return true;
        }
        if ("Private".equals(access)) {
            return false;
        }
        return !"Shared".equals(access)
            || visibilityRepository.hasDirectPermission(type.railsName(), version.getItemId(), userId);
    }

    private static boolean sameId(Object value, long userId) {
        if (value instanceof Number number) {
            return number.longValue() == userId;
        }
        return value != null && value.toString().equals(Long.toString(userId));
    }

    private void scrubSecrets(ObjectNode version) {
        scrub(version, "object");
        scrub(version, "object_changes");
    }

    private void scrub(ObjectNode version, String key) {
        if (version.path(key).isTextual()) {
            version.put(key, removeSecretEntries(version.path(key).asText()));
        }
    }

    private static String removeSecretEntries(String yaml) {
        String[] lines = yaml.split("(?<=\\n)", -1);
        StringBuilder result = new StringBuilder(yaml.length());
        boolean removing = false;
        for (String line : lines) {
            String content = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
            Matcher matcher = TOP_LEVEL_KEY.matcher(content);
            if (matcher.find()) {
                removing = SECRET_KEY.matcher(matcher.group(1)).matches();
            } else if (!(content.startsWith(" ") || content.startsWith("- "))) {
                removing = false;
            }
            if (!removing) {
                result.append(line);
            }
        }
        return result.toString();
    }
}
