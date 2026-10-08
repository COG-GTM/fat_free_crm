package com.fatfreecrm.service.write.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fatfreecrm.domain.Field;
import com.fatfreecrm.domain.FieldGroup;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.repository.FieldGroupRepository;
import com.fatfreecrm.service.validation.ActiveModelMessages;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.service.write.RailsInternalError;
import com.fatfreecrm.service.write.RailsParams;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code Admin::FieldGroupsController} writes. {@code FieldGroup} validates label presence,
 * derives {@code name} from the label in {@code before_save}, and on destroy moves its fields to the
 * klass's {@code custom_fields} group ({@code not_default_field_group} returns false without
 * {@code throw(:abort)}, so the default group itself is destroyable — mirrored). No PaperTrail.
 */
@Service
public class AdminFieldGroupWriteService {

    private static final String MODEL = "field_group";

    private final FieldGroupRepository fieldGroupRepository;
    private final ActiveModelMessages messages;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    public AdminFieldGroupWriteService(FieldGroupRepository fieldGroupRepository,
        ActiveModelMessages messages, EntityManager entityManager, JdbcTemplate jdbcTemplate) {
        this.fieldGroupRepository = fieldGroupRepository;
        this.messages = messages;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public FieldGroup create(RailsParams params) {
        FieldGroup group = new FieldGroup();
        apply(group, params);
        validateAndName(group);
        return fieldGroupRepository.saveAndFlush(group);
    }

    @Transactional
    public void update(long id, RailsParams params) {
        FieldGroup group = find(id);
        apply(group, params);
        validateAndName(group);
        fieldGroupRepository.saveAndFlush(group);
    }

    @Transactional
    public void destroy(long id) {
        FieldGroup group = find(id);
        var query = entityManager.createQuery(
            "SELECT g FROM FieldGroup g WHERE g.name = 'custom_fields' AND "
                + (group.getKlassName() == null ? "g.klassName IS NULL" : "g.klassName = :klass")
                + " ORDER BY g.id", FieldGroup.class);
        if (group.getKlassName() != null) {
            query.setParameter("klass", group.getKlassName());
        }
        List<FieldGroup> defaults = query.setMaxResults(1).getResultList();
        if (defaults.isEmpty()) {
            throw new RailsInternalError("undefined method 'fields' for nil (move_fields_to_default_field_group)");
        }
        FieldGroup target = defaults.get(0);
        List<Field> fields = entityManager.createQuery(
                "SELECT f FROM Field f WHERE f.fieldGroup.id = :id ORDER BY f.position", Field.class)
            .setParameter("id", id)
            .getResultList();
        for (Field field : fields) {
            if (!Objects.equals(target.getId(), id)) {
                field.setFieldGroup(target);
            }
        }
        entityManager.flush();
        fieldGroupRepository.delete(group);
        fieldGroupRepository.flush();
    }

    /** {@code POST /admin/field_groups/sort}: update_all positions (no timestamps). */
    @Transactional
    public void sort(Map<String, JsonNode> params) {
        String asset = params == null || params.get("asset") == null || params.get("asset").isNull()
            ? "" : params.get("asset").asText();
        JsonNode ids = params == null ? null : params.get(asset + "_field_groups");
        if (ids == null || ids.isNull()) {
            throw new RailsInternalError("undefined method 'each_with_index' for nil");
        }
        int index = 0;
        for (JsonNode id : ids.isArray() ? ids : List.of(ids)) {
            index++;
            Integer groupId = RailsParams.asInteger(id);
            if (groupId != null) {
                jdbcTemplate.update("UPDATE field_groups SET position = ? WHERE id = ?", index, groupId);
            }
        }
    }

    private FieldGroup find(long id) {
        return fieldGroupRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("FieldGroup " + id + " was not found"));
    }

    private void apply(FieldGroup group, RailsParams params) {
        params.assignString("name", group::setName);
        params.assignString("label", group::setLabel);
        params.assignInteger("position", group::setPosition);
        params.assignString("hint", group::setHint);
        params.assignInteger("tag_id", tagId -> group.setTag(
            tagId == null ? null : entityManager.getReference(Tag.class, tagId.longValue())));
        params.assignString("klass_name", group::setKlassName);
    }

    private void validateAndName(FieldGroup group) {
        String label = group.getLabel();
        if (label == null || label.isBlank()) {
            RailsErrors errors = new RailsErrors();
            errors.add(messages, MODEL, "label", "blank");
            throw new RailsValidationException(errors);
        }
        if (group.getName() == null || group.getName().isBlank()) {
            group.setName(label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"));
        }
    }
}
