package com.fatfreecrm.service.write.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fatfreecrm.domain.Group;
import com.fatfreecrm.repository.GroupRepository;
import com.fatfreecrm.service.validation.ActiveModelMessages;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.service.write.RailsParams;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rails {@code Admin::GroupsController} writes: {@code group_params} = {@code name, user_ids: []};
 * {@code Group} validates name presence + (case-sensitive) uniqueness. No PaperTrail.
 */
@Service
public class AdminGroupWriteService {

    private static final String MODEL = "group";

    private final GroupRepository groupRepository;
    private final ActiveModelMessages messages;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate immediateTransaction;
    private final Clock clock;

    public AdminGroupWriteService(
        GroupRepository groupRepository,
        ActiveModelMessages messages,
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        this.groupRepository = groupRepository;
        this.messages = messages;
        this.jdbcTemplate = jdbcTemplate;
        this.immediateTransaction = new TransactionTemplate(transactionManager);
        this.immediateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Transactional
    public Group create(RailsParams params) {
        Group group = new Group();
        params.assignString("name", group::setName);
        validate(group);
        Instant now = now();
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        group = groupRepository.saveAndFlush(group);
        if (params.provided("user_ids")) {
            replaceUsers(group.getId(), userIds(params.get("user_ids").orElse(null)));
        }
        return group;
    }

    @Transactional
    public void update(long id, RailsParams params) {
        Group group = find(id);
        if (params.provided("user_ids")) {
            // habtm ids= on a persisted record writes immediately, even when validation fails.
            List<Long> userIds = userIds(params.get("user_ids").orElse(null));
            immediateTransaction.executeWithoutResult(status -> replaceUsers(id, userIds));
        }
        String before = group.getName();
        params.assignString("name", group::setName);
        validate(group);
        if (!Objects.equals(before, group.getName())) {
            group.setUpdatedAt(now());
            groupRepository.saveAndFlush(group);
        }
    }

    @Transactional
    public void destroy(long id) {
        Group group = find(id);
        jdbcTemplate.update("DELETE FROM groups_users WHERE group_id = ?", id);
        groupRepository.delete(group);
        groupRepository.flush();
    }

    private Group find(long id) {
        return groupRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Group " + id + " was not found"));
    }

    private void validate(Group group) {
        RailsErrors errors = new RailsErrors();
        String name = group.getName();
        if (name == null || name.isBlank()) {
            errors.add(messages, MODEL, "name", "blank");
        }
        String sql = name == null ? "SELECT count(*) FROM groups WHERE name IS NULL"
            : "SELECT count(*) FROM groups WHERE name = ?";
        List<Object> args = new ArrayList<>();
        if (name != null) {
            args.add(name);
        }
        if (group.getId() != null) {
            sql += " AND id <> ?";
            args.add(group.getId());
        }
        Long taken = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        if (taken != null && taken > 0) {
            errors.add(messages, MODEL, "name", "taken");
        }
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }

    private List<Long> userIds(JsonNode node) {
        Set<Long> ids = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String text = item.isNull() ? "" : item.asText().strip();
                if (!text.isEmpty()) {
                    ids.add(Long.parseLong(text));
                }
            }
        }
        for (Long userId : ids) {
            Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?",
                Long.class, userId);
            if (count == null || count == 0) {
                throw new EntityNotFoundException("User " + userId + " was not found");
            }
        }
        return new ArrayList<>(ids);
    }

    private void replaceUsers(long groupId, List<Long> userIds) {
        List<Long> existing = jdbcTemplate.queryForList(
            "SELECT user_id FROM groups_users WHERE group_id = ?", Long.class, groupId);
        for (Long userId : existing) {
            if (!userIds.contains(userId)) {
                jdbcTemplate.update("DELETE FROM groups_users WHERE group_id = ? AND user_id = ?",
                    groupId, userId);
            }
        }
        for (Long userId : userIds) {
            if (!existing.contains(userId)) {
                jdbcTemplate.update("INSERT INTO groups_users (group_id, user_id) VALUES (?, ?)",
                    groupId, userId);
            }
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
