package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

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
    String bodyCompare,
    boolean reset,
    JsonNode dbAssert,
    List<SetupRequest> setup,
    JsonNode setupSql
) {
    public static final String TEXT_BODY = "text";

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
            description, expect, null, reset, dbAssert, List.of(), null);
    }

    public List<String> setupStatements() {
        List<String> statements = new ArrayList<>();
        if (setupSql != null && setupSql.isArray()) {
            setupSql.forEach(node -> {
                if (node.isTextual()) {
                    statements.add(node.asText());
                }
            });
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
            description, expect, null, false, null, List.of(), null);
    }

    public boolean textBody() {
        return TEXT_BODY.equals(bodyCompare);
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
            description, null, null, false, null, List.of(), null);
    }

    public record SideRequest(String path, Target target, String bodyPointer, String accept) {
        public SideRequest(String path, Target target) {
            this(path, target, null, null);
        }

        public SideRequest(String path, Target target, String bodyPointer) {
            this(path, target, bodyPointer, null);
        }
    }

    public record SetupRequest(
        String method,
        SideRequest rails,
        SideRequest spring,
        JsonNode params,
        JsonNode body,
        String auth
    ) {
    }

    public enum Target {
        RAILS,
        SPRING
    }
}
