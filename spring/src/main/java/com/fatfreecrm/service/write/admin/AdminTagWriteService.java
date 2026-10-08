package com.fatfreecrm.service.write.admin;

import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.repository.TagRepository;
import com.fatfreecrm.service.validation.ActiveModelMessages;
import com.fatfreecrm.service.validation.RailsErrors;
import com.fatfreecrm.service.validation.RailsValidationException;
import com.fatfreecrm.service.write.RailsParams;
import jakarta.persistence.EntityNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rails {@code Admin::TagsController} writes ({@code tag_params} = {@code name, taggings_count}).
 * {@code ActsAsTaggableOn::Tag} validates name presence, case-sensitive uniqueness and length
 * &lt;= 255. {@code before_destroy :no_associated_field_groups} returns false without
 * {@code throw(:abort)}, so it never halts (mirrored). No timestamps, no PaperTrail.
 */
@Service
public class AdminTagWriteService {

    private static final String MODEL = "tag";

    private final TagRepository tagRepository;
    private final ActiveModelMessages messages;
    private final JdbcTemplate jdbcTemplate;

    public AdminTagWriteService(TagRepository tagRepository, ActiveModelMessages messages,
        JdbcTemplate jdbcTemplate) {
        this.tagRepository = tagRepository;
        this.messages = messages;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Tag create(RailsParams params) {
        Tag tag = new Tag();
        apply(tag, params);
        validate(tag);
        return tagRepository.saveAndFlush(tag);
    }

    @Transactional
    public void update(long id, RailsParams params) {
        Tag tag = find(id);
        apply(tag, params);
        validate(tag);
        tagRepository.saveAndFlush(tag);
    }

    @Transactional
    public void destroy(long id) {
        tagRepository.delete(find(id));
        tagRepository.flush();
    }

    private Tag find(long id) {
        return tagRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Tag " + id + " was not found"));
    }

    private static void apply(Tag tag, RailsParams params) {
        params.assignString("name", tag::setName);
        params.assignInteger("taggings_count", tag::setTaggingsCount);
    }

    private void validate(Tag tag) {
        RailsErrors errors = new RailsErrors();
        String name = tag.getName();
        if (name == null || name.isBlank()) {
            errors.add(messages, MODEL, "name", "blank");
        }
        String sql = name == null ? "SELECT count(*) FROM tags WHERE name IS NULL"
            : "SELECT count(*) FROM tags WHERE name = ?";
        List<Object> args = new ArrayList<>();
        if (name != null) {
            args.add(name);
        }
        if (tag.getId() != null) {
            sql += " AND id <> ?";
            args.add(tag.getId());
        }
        Long taken = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        if (taken != null && taken > 0) {
            errors.add(messages, MODEL, "name", "taken");
        }
        if (name != null && name.codePointCount(0, name.length()) > 255) {
            errors.add("name", messages.generateMessage(MODEL, "name", "too_long.other",
                Map.of("count", 255)));
        }
        if (!errors.isEmpty()) {
            throw new RailsValidationException(errors);
        }
    }
}
