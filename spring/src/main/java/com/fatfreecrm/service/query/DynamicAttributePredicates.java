package com.fatfreecrm.service.query;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;

/**
 * Extension point for search attributes that are not static entity fields (Rails custom fields, cf_*).
 * AB-269 consults registered beans after its static whitelist; AB-271 implements it.
 */
public interface DynamicAttributePredicates {

    boolean handles(Class<?> entityType, String attribute);

    /** {@code operator} is the Ransack suffix (eq, not_eq, cont, start, lt, lteq, gt, gteq, in, null, ...). */
    <T> Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                              Class<T> entityType, String attribute, String operator, List<String> rawValues);
}
