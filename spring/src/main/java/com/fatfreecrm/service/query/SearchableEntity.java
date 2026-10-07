package com.fatfreecrm.service.query;

import com.fatfreecrm.security.AuthenticatedUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Predicate;
import java.util.Map;

/**
 * Per-entity list-query metadata: Rails model name, default page size, sort whitelist,
 * ransackable association joins, text search, taggability and sidebar facets.
 */
public record SearchableEntity(
    Class<?> entityClass,
    String railsName,
    int defaultPerPage,
    SortWhitelist sortWhitelist,
    Map<String, AssociationDef> associations,
    TextSearch textSearch,
    boolean taggable,
    FacetProvider facets
) {

    public SearchableEntity {
        associations = Map.copyOf(associations);
    }

    /** A whitelisted association: the join factory plus the target entity type. */
    record AssociationDef(AssociationJoin join, Class<?> targetClass) {
    }

    /** Mirrors one Rails {@code text_search} scope against the query entity (root or subquery root). */
    @FunctionalInterface
    public interface TextSearch {

        Predicate search(From<?, ?> root, CriteriaBuilder cb, String query);
    }

    /** Produces sidebar facet maps (e.g. {@code {category: {affiliate: 0, ..., all: 3}}}). */
    @FunctionalInterface
    public interface FacetProvider {

        Map<String, Map<String, Long>> facets(AuthenticatedUser user);
    }
}
