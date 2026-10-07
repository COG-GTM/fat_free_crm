package com.fatfreecrm.service.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import com.fatfreecrm.domain.Tag;
import com.fatfreecrm.domain.Tagging;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Parses the {@code query} parameter like Rails {@code parse_query_and_tags}: words become the
 * text-search query, {@code #}-prefixed tokens become acts-as-taggable-on tag filters.
 */
public record SearchText(String text, List<String> tags, boolean tagFilterRequested) {

    public SearchText {
        tags = List.copyOf(tags);
    }

    private static final char TAG_ESCAPE = '!';

    /** Result when the query string carries tag markers but parses to zero tags ({@code tagged_with} none). */
    public static SearchText none() {
        return new SearchText(null, List.of(), true);
    }

    public static SearchText parse(String raw, boolean taggable) {
        if (raw == null || raw.isBlank()) {
            return new SearchText(null, List.of(), false);
        }
        String query;
        String tagString = null;
        boolean tagsSeen = false;
        if (raw.startsWith("#") && raw.endsWith("#")) {
            tagString = raw.substring(1, raw.length() - 1);
            tagsSeen = true;
            query = null;
        } else {
            List<String> words = new ArrayList<>();
            List<String> tagTokens = new ArrayList<>();
            for (String token : raw.strip().split("\\s+")) {
                if (token.startsWith("#")) {
                    tagTokens.add(token.substring(1));
                } else {
                    words.add(token);
                }
            }
            if (!tagTokens.isEmpty()) {
                tagString = String.join(", ", tagTokens);
                tagsSeen = true;
            }
            query = words.isEmpty() ? null : String.join(" ", words);
        }
        if (taggable && tagsSeen && tagString != null && !tagString.isEmpty()) {
            List<String> tags = parseTagList(tagString);
            if (tags.isEmpty()) {
                return none();
            }
            return new SearchText(query, tags, true);
        }
        if (tagsSeen && tagString != null && tagString.isEmpty() && query == null) {
            return none();
        }
        return new SearchText(query, List.of(), false);
    }

    /** acts-as-taggable-on {@code DefaultParser}: comma-separated, quoted tags supported. */
    static List<String> parseTagList(String value) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        int index = 0;
        int length = value.length();
        while (index < length) {
            while (index < length && (value.charAt(index) == ',' || Character.isWhitespace(value.charAt(index)))) {
                index++;
            }
            if (index >= length) {
                break;
            }
            char first = value.charAt(index);
            String token;
            if (first == '"' || first == '\'') {
                int close = value.indexOf(first, index + 1);
                if (close < 0) {
                    token = value.substring(index + 1);
                    index = length;
                } else {
                    token = value.substring(index + 1, close);
                    index = close + 1;
                }
            } else {
                int comma = value.indexOf(',', index);
                if (comma < 0) {
                    token = value.substring(index);
                    index = length;
                } else {
                    token = value.substring(index, comma);
                    index = comma + 1;
                }
            }
            token = token.strip();
            if (!token.isEmpty()) {
                tags.add(token);
            }
        }
        return new ArrayList<>(tags);
    }

    /**
     * One {@code EXISTS (tagging where taggable = root and context = 'tags' and tag_id in
     * (select id from tags where lower(name) like pattern escape '!'))} per tag, all ANDed.
     */
    public Predicate tagPredicate(
        Root<?> root, CriteriaQuery<?> query, CriteriaBuilder cb, String railsName, String tag) {
        Subquery<Long> tagIds = query.subquery(Long.class);
        Root<Tag> tagRoot = tagIds.from(Tag.class);
        tagIds.select(tagRoot.get("id"));
        String pattern = tag.toLowerCase(Locale.ROOT)
            .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        tagIds.where(cb.like(cb.lower(tagRoot.get("name")), pattern, TAG_ESCAPE));

        Subquery<Long> tagging = query.subquery(Long.class);
        Root<Tagging> taggingRoot = tagging.from(Tagging.class);
        tagging.select(taggingRoot.get("id"));
        Expression<Long> taggableId = taggingRoot.get("taggableId").as(Long.class);
        tagging.where(cb.and(
            cb.equal(taggableId, root.get("id")),
            cb.equal(taggingRoot.get("taggableType"), railsName),
            cb.equal(taggingRoot.get("context"), "tags"),
            taggingRoot.get("tag").get("id").in(tagIds)
        ));
        return cb.exists(tagging);
    }
}
