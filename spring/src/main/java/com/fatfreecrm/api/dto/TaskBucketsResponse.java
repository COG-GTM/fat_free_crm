package com.fatfreecrm.api.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code GET /api/v1/tasks}: Rails {@code Task.find_all_grouped} buckets, in Setting order. */
public record TaskBucketsResponse(Map<String, List<ObjectNode>> buckets) {

    public TaskBucketsResponse {
        buckets = Collections.unmodifiableMap(new LinkedHashMap<>(buckets));
    }
}
