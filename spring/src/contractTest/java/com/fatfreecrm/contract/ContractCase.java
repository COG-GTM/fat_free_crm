package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;

public record ContractCase(
    String id,
    String ticket,
    String status,
    String method,
    String path,
    SideRequest rails,
    SideRequest spring,
    JsonNode params,
    JsonNode body,
    String auth,
    JsonNode normalize,
    String description,
    JsonNode expect
) {
    public ContractCase(
        String id,
        String ticket,
        String status,
        String method,
        String path,
        SideRequest rails,
        SideRequest spring,
        JsonNode params,
        JsonNode body,
        String auth,
        JsonNode normalize,
        String description
    ) {
        this(id, ticket, status, method, path, rails, spring, params, body, auth, normalize, description, null);
    }

    public record SideRequest(String path, Target target) {
    }

    public enum Target {
        RAILS,
        SPRING
    }
}
