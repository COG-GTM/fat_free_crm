package com.fatfreecrm.service.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import java.util.Arrays;
import java.util.List;

public record StateFilter(String param, StatePredicate predicate) {

    public List<String> values(String raw) {
        return raw == null || raw.isEmpty() ? List.of() : Arrays.asList(raw.split(","));
    }

    @FunctionalInterface
    public interface StatePredicate {

        Predicate create(From<?, ?> root, CriteriaBuilder builder, List<String> values);
    }
}
