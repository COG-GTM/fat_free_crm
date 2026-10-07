package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class CapturedResponseTest {

    @Test
    void normalisesTheMediaTypeAndParsesJsonBodies() throws Exception {
        CapturedResponse response = capture(200, "Application/JSON; charset=UTF-8", "{\"id\":1}");
        assertEquals(200, response.status());
        assertEquals("application/json", response.mediaType());
        assertEquals("{\"id\":1}", response.rawBody());
        assertNotNull(response.json());
        assertEquals(1, response.json().path("id").asInt());
    }

    @Test
    void parsesStructuredJsonSuffixMediaTypes() throws Exception {
        CapturedResponse problem = capture(404, "application/problem+json", "{\"title\":\"Not Found\",\"status\":404}");
        assertEquals("application/problem+json", problem.mediaType());
        assertEquals("Not Found", problem.json().path("title").asText());

        CapturedResponse vendor = capture(200, "application/vnd.ffcrm+json; charset=utf-8", "[1,2]");
        assertEquals("application/vnd.ffcrm+json", vendor.mediaType());
        assertEquals(2, vendor.json().size());
    }

    @Test
    void doesNotParseNonJsonMediaTypesEvenWhenTheBodyLooksLikeJson() throws Exception {
        CapturedResponse html = capture(200, "text/html; charset=utf-8", "{\"id\":1}");
        assertEquals("text/html", html.mediaType());
        assertNull(html.json());
        assertEquals("{\"id\":1}", html.rawBody());
    }

    @Test
    void missingContentTypeYieldsAnEmptyMediaTypeAndNoJson() throws Exception {
        CapturedResponse response = capture(200, null, "{\"id\":1}");
        assertEquals("", response.mediaType());
        assertNull(response.json());
        assertEquals("{\"id\":1}", response.rawBody());
    }

    @Test
    void malformedOrBlankJsonBodiesKeepTheRawBodyWithoutAParsedTree() throws Exception {
        CapturedResponse malformed = capture(500, "application/json", "{\"id\":");
        assertEquals(500, malformed.status());
        assertNull(malformed.json());
        assertEquals("{\"id\":", malformed.rawBody());

        CapturedResponse blank = capture(200, "application/json", "   ");
        assertNull(blank.json());
        assertEquals("   ", blank.rawBody());
    }

    private static CapturedResponse capture(int status, String contentType, String body) throws Exception {
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, status, contentType, body))) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.url() + "/capture")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return CapturedResponse.from(response);
        }
    }
}
