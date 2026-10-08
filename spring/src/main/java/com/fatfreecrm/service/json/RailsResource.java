package com.fatfreecrm.service.json;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public record RailsResource(
    String railsModel,
    String table,
    Class<?> entityClass,
    Set<String> yamlArrayColumns,
    boolean taggable,
    Set<String> excludedColumns,
    String controllerName,
    Function<Object, String> autocompleteText,
    Map<String, RelatedExclusion> relatedExclusions
) {

    public RailsResource {
        if (!table.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid Rails table name: " + table);
        }
        yamlArrayColumns = Set.copyOf(yamlArrayColumns);
        excludedColumns = Set.copyOf(excludedColumns);
        relatedExclusions = Map.copyOf(relatedExclusions);
    }

    public record RelatedExclusion(Function<Long, Set<Long>> ids) {
    }
}
