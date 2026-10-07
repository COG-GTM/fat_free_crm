package com.fatfreecrm.spike.customfields;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

/**
 * Criteria {@link Specification}s built on the spike-prefixed jsonb functions.
 * The containment spec serializes the search map to a JSON string parameter so
 * the pattern's {@code cast(?2 as jsonb)} applies server-side.
 */
public final class CustomFieldSpecifications {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CustomFieldSpecifications() {
    }

    public static <T> Specification<T> contains(String attribute, Map<String, Object> json) {
        String serialized;
        try {
            serialized = MAPPER.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return (root, query, cb) -> cb.isTrue(
            cb.function("spike_jsonb_contains", Boolean.class, root.get(attribute), cb.literal(serialized)));
    }

    public static <T> Specification<T> pathMatches(String attribute, String jsonPath) {
        return (root, query, cb) -> {
            Predicate pred = cb.isTrue(
                cb.function("spike_jsonb_path_match", Boolean.class, root.get(attribute), cb.literal(jsonPath)));
            return pred;
        };
    }
}
