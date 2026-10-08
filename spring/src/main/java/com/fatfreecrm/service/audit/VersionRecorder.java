package com.fatfreecrm.service.audit;

import com.fatfreecrm.domain.Version;
import com.fatfreecrm.domain.AccountContact;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.User;
import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.VersionRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code versions} rows the way PaperTrail 16 does in Rails, in the same transaction as the
 * audited write:
 *
 * <ul>
 *   <li>create: {@code object} NULL, {@code object_changes} = attribute → [column default, value]
 *   for every attribute whose value differs from its column default, in column order</li>
 *   <li>update: {@code object} = the pre-change attribute dump, {@code object_changes} =
 *   attribute → [before, after] for non-ignored changed attributes in column order; a version is
 *   written only when a non-ignored attribute changed</li>
 *   <li>destroy: {@code object} = the final attribute dump, {@code object_changes} =
 *   attribute → [value, nil] for every non-ignored attribute in column order</li>
 *   <li>{@link #recordEvent} covers TaskObserver's {@code complete}/{@code reassign}/
 *   {@code reschedule} rows: no object/changes, no related</li>
 * </ul>
 *
 * {@code whodunnit} is the user id as a string, {@code transaction_id} stays NULL, and
 * {@code created_at} comes from the injectable {@link Clock}.
 */
@Service
public class VersionRecorder {

    private final VersionRepository versionRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public VersionRecorder(VersionRepository versionRepository, EntityManager entityManager, Clock clock) {
        this.versionRepository = versionRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * PaperTrail {@code create} version. {@code attributes} must be the post-insert attribute dump
     * in column order (timestamps populated) and {@code defaults} the per-column defaults
     * ({@code "name" -> ""} on Task); entries whose value equals the default are omitted.
     */
    @Transactional
    public Version recordCreate(
        AuthenticatedUser user,
        Object entity,
        Map<String, Object> attributes,
        Map<String, Object> defaults
    ) {
        PaperTrailOptions options = options(entity);
        Map<String, Object[]> changes = new LinkedHashMap<>();
        attributes.forEach((name, value) -> {
            if (options.ignore().contains(name)) {
                return;
            }
            Object initial = defaults.getOrDefault(name, null);
            if (!Objects.equals(initial, value)) {
                changes.put(name, new Object[] {initial, value});
            }
        });
        Version version = base(user, options, entity, "create");
        version.setObjectChanges(PaperTrailYaml.dumpChanges(changes));
        return versionRepository.save(version);
    }

    /**
     * PaperTrail {@code update} version; {@code before} is the pre-change attribute dump in column
     * order and {@code after} the post-change one. Returns null when nothing non-ignored changed.
     */
    @Transactional
    public Version recordUpdate(
        AuthenticatedUser user,
        Object entity,
        Map<String, Object> before,
        Map<String, Object> after
    ) {
        return recordUpdate(user, entity, before, after, java.util.List.of());
    }

    /**
     * PaperTrail {@code update} version. {@code assignedOrder} is retained for callers that track
     * request assignment order; PaperTrail serializes both YAML fields in model column order.
     */
    @Transactional
    public Version recordUpdate(
        AuthenticatedUser user,
        Object entity,
        Map<String, Object> before,
        Map<String, Object> after,
        java.util.List<String> assignedOrder
    ) {
        PaperTrailOptions options = options(entity);
        Map<String, Object[]> changes = new LinkedHashMap<>();
        after.forEach((name, value) -> {
            if (options.ignore().contains(name)) {
                return;
            }
            Object old = before.get(name);
            if (!Objects.equals(old, value)) {
                changes.put(name, new Object[] {old, value});
            }
        });
        // Rails does not record an automatic timestamp-only save; an explicitly assigned
        // updated_at still creates the PaperTrail version.
        boolean notable = changes.keySet().stream()
            .anyMatch(name -> !name.equals("created_at")
                && (!name.equals("updated_at") || assignedOrder.contains(name)));
        if (!notable) {
            return null;
        }
        Version version = base(user, options, entity, "update");
        version.setObject(PaperTrailYaml.dumpObject(before));
        version.setObjectChanges(PaperTrailYaml.dumpChanges(changes));
        return versionRepository.save(version);
    }

    /** PaperTrail {@code touch} is a forced update with the touched object and no changes YAML. */
    @Transactional
    public Version recordTouch(
        AuthenticatedUser user,
        Object entity,
        Map<String, Object> before,
        Map<String, Object> after
    ) {
        Objects.requireNonNull(after, "after");
        PaperTrailOptions options = options(entity);
        boolean notable = after.entrySet().stream()
            .anyMatch(entry -> !options.ignore().contains(entry.getKey())
                && !Objects.equals(before.get(entry.getKey()), entry.getValue()));
        if (!notable) {
            return null;
        }
        Version version = base(user, options, entity, "update");
        version.setObject(PaperTrailYaml.dumpObject(after));
        return versionRepository.save(version);
    }

    /** PaperTrail {@code destroy} version; {@code attributes} is the final attribute dump. */
    @Transactional
    public Version recordDestroy(AuthenticatedUser user, Object entity, Map<String, Object> attributes) {
        PaperTrailOptions options = options(entity);
        Map<String, Object[]> changes = new LinkedHashMap<>();
        attributes.forEach((name, value) -> {
            if (!options.ignore().contains(name)) {
                changes.put(name, new Object[] {value, null});
            }
        });
        Version version = base(user, options, entity, "destroy");
        version.setObject(PaperTrailYaml.dumpObject(attributes));
        version.setObjectChanges(PaperTrailYaml.dumpChanges(changes));
        return versionRepository.save(version);
    }

    /**
     * TaskObserver {@code log_activity} row: {@code event} is "complete"/"reassign"/"reschedule",
     * object/changes/related stay NULL, whodunnit mirrors {@code PaperTrail.request.whodunnit}.
     */
    @Transactional
    public Version recordEvent(AuthenticatedUser user, RailsModelType itemType, Long itemId, String event) {
        Version version = new Version();
        version.setItemModelType(itemType);
        version.setItemId(itemId.intValue());
        version.setEvent(event);
        version.setWhodunnit(whodunnit(user));
        version.setCreatedAt(now());
        return versionRepository.save(version);
    }

    private Version base(AuthenticatedUser user, PaperTrailOptions options, Object entity, String event) {
        Version version = new Version();
        version.setItemType(options.itemType().railsName());
        Long id = entity instanceof User auditUser ? auditUser.getId() : ((BaseEntity) entity).getId();
        version.setItemId(id.intValue());
        version.setEvent(event);
        version.setWhodunnit(whodunnit(user));
        version.setCreatedAt(now());
        if (options.relatedAttribute() != null) {
            Related related = relatedOf(entity, options.relatedAttribute());
            version.setRelatedType(related.type());
            version.setRelatedId(related.id());
        }
        return version;
    }

    private record Related(String type, Integer id) {
    }

    private static Related relatedOf(Object entity, String attribute) {
        try {
            Related value = switch (entity) {
                case com.fatfreecrm.domain.Task task -> attribute.equals("asset")
                    ? pair(task.getAssetType(), task.getAssetId()) : null;
                case com.fatfreecrm.domain.Comment comment -> attribute.equals("commentable")
                    ? pair(comment.getCommentableType(), comment.getCommentableId()) : null;
                case com.fatfreecrm.domain.Email email -> attribute.equals("mediator")
                    ? pair(email.getMediatorType(), email.getMediatorId()) : null;
                case Address address -> attribute.equals("addressable")
                    ? pair(address.getAddressableType(), address.getAddressableId()) : null;
                case AccountContact accountContact -> attribute.equals("contact")
                    ? pair("Contact", accountContact.getContact() == null
                        ? null : accountContact.getContact().getId().intValue())
                    : null;
                default -> null;
            };
            return value == null ? new Related(null, null) : value;
        } catch (RuntimeException exception) {
            return new Related(null, null);
        }
    }

    private static Related pair(String type, Integer id) {
        return new Related(type, id);
    }

    private static PaperTrailOptions options(Object entity) {
        return PaperTrailOptions.forClass(entity.getClass())
            .orElseThrow(() -> new IllegalArgumentException(
                "No PaperTrail options for " + entity.getClass().getName()));
    }

    private static String whodunnit(AuthenticatedUser user) {
        return user == null || user.id() == null ? null : user.id().toString();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
