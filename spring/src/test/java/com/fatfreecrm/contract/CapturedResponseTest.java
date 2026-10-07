package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class CapturedResponseTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void parsesJsonAndLowercasesMediaTypeWithoutParameters() throws Exception {
        CapturedResponse captured = capture(200, "Application/JSON; charset=UTF-8", "{\"ok\":true}");
        assertEquals(200, captured.status());
        assertEquals("application/json", captured.mediaType());
        assertTrue(captured.json().path("ok").asBoolean());
        assertEquals("{\"ok\":true}", captured.rawBody());
    }

    @Test
    void parsesStructuredJsonSuffixMediaTypes() throws Exception {
        CapturedResponse captured = capture(404, "application/problem+json", "{\"status\":404}");
        assertEquals(404, captured.status());
        assertEquals("application/problem+json", captured.mediaType());
        assertEquals(404, captured.json().path("status").asInt());
    }

    @Test
    void keepsRawBodyButNoJsonTreeForMalformedJson() throws Exception {
        CapturedResponse captured = capture(500, "application/json", "{not json");
        assertNull(captured.json());
        assertEquals("{not json", captured.rawBody());
    }

    @Test
    void blankJsonBodyHasNoJsonTree() throws Exception {
        CapturedResponse captured = capture(200, "application/json", "   ");
        assertNull(captured.json());
        assertEquals("   ", captured.rawBody());
    }

    @Test
    void nonJsonMediaTypesAreNeverParsed() throws Exception {
        CapturedResponse captured = capture(200, "text/plain", "{\"ok\":true}");
        assertNull(captured.json());
        assertEquals("text/plain", captured.mediaType());
        assertEquals("{\"ok\":true}", captured.rawBody());
    }

    @Test
    void missingContentTypeYieldsEmptyMediaType() throws Exception {
        CapturedResponse captured = capture(200, null, "body");
        assertEquals("", captured.mediaType());
        assertNull(captured.json());
        assertEquals("body", captured.rawBody());
    }

    private static CapturedResponse capture(int status, String contentType, String body) throws Exception {
        try (StubServer server = new StubServer(
            exchange -> StubServer.respond(exchange, status, contentType, body))) {
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder(URI.create(server.url() + "/"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            return CapturedResponse.from(response);
        }
    }
}
