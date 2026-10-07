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
    String description
) {

    public record SideRequest(String path, Target target) {
    }

    public enum Target {
        RAILS,
        SPRING
    }
}
