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
    JsonNode expect,
    boolean reset,
    JsonNode dbAssert,
    JsonNode setup
) {
    /** Cases without per-case setup SQL. */
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
        String description,
        JsonNode expect,
        boolean reset,
        JsonNode dbAssert
    ) {
        this(id, ticket, status, method, path, rails, spring, params, body, auth, normalize,
            description, expect, reset, dbAssert, null);
    }

    /** {@code setup}: SQL statements replayed after each side's reset (reset cases only). */
    public java.util.List<String> setupStatements() {
        java.util.List<String> statements = new java.util.ArrayList<>();
        if (setup != null && setup.isArray()) {
            setup.forEach(node -> statements.add(node.asText()));
        }
        return statements;
    }

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
        String description,
        JsonNode expect
    ) {
        this(id, ticket, status, method, path, rails, spring, params, body, auth, normalize,
            description, expect, false, null, null);
    }

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
        this(id, ticket, status, method, path, rails, spring, params, body, auth, normalize,
            description, null, false, null, null);
    }

    public record SideRequest(String path, Target target, String bodyPointer) {
        public SideRequest(String path, Target target) {
            this(path, target, null);
        }
    }

    public enum Target {
        RAILS,
        SPRING
    }
}
