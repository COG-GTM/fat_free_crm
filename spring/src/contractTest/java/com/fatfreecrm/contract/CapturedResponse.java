package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.Locale;

public record CapturedResponse(int status, String mediaType, String rawBody, JsonNode json) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static CapturedResponse from(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        JsonNode parsed = null;
        if ((mediaType.equals("application/json") || mediaType.endsWith("+json")) && !response.body().isBlank()) {
            try {
                parsed = JSON.readTree(response.body());
            } catch (Exception ignored) {
                parsed = null;
            }
        }
        return new CapturedResponse(response.statusCode(), mediaType, response.body(), parsed);
    }
}
