package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RailsSessionAuthTest {
    private static final Map<String, FixtureUsers.FixtureUser> USERS = Map.of(
        "admin", new FixtureUsers.FixtureUser("admin", 1, "admin", "admin@contract.example", "contract-password",
            true, false)
    );
    private static final String META_TOKEN_PAGE = "<html><head><meta name=\"csrf-token\" content=\"page-token\" />"
        + "</head></html>";

    @Test
    void serverErrorsAndMissingSignInPagesAreUnreachable() throws Exception {
        AtomicInteger status = new AtomicInteger(500);
        try (StubServer server = new StubServer(exchange ->
            StubServer.respond(exchange, status.get(), "text/html", "down"))) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url() + "/", USERS);

            IOException serverError = assertThrows(IOException.class, () -> auth.authenticate("admin"));
            assertEquals("Rails sign-in page is unreachable (HTTP 500) at " + server.url() + "/users/sign_in",
                serverError.getMessage());

            status.set(404);
            IOException notFound = assertThrows(IOException.class, () -> auth.authenticate("admin"));
            assertEquals("Rails sign-in page is unreachable (HTTP 404) at " + server.url() + "/users/sign_in",
                notFound.getMessage());
        }
    }

    @Test
    void connectionFailuresAreReportedAsUnreachable() throws Exception {
        String url;
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200, null, ""))) {
            url = server.url();
        }
        RailsSessionAuth auth = new RailsSessionAuth(url, USERS);
        IOException error = assertThrows(IOException.class, () -> auth.authenticate("admin"));
        assertEquals("Rails sign-in page is unreachable at " + url + "/users/sign_in", error.getMessage());
    }

    @Test
    void unknownFixtureUsersAreRejectedBeforeContactingRails() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            hits.incrementAndGet();
            StubServer.respond(exchange, 200, "text/html", META_TOKEN_PAGE);
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), USERS);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> auth.authenticate("zed"));
            assertEquals("Unknown contract fixture user: zed", error.getMessage());
            assertEquals(0, hits.get());
        }
    }

    @Test
    void missingCsrfTokenIsUnavailableAndIsNotCached() throws Exception {
        AtomicInteger pageHits = new AtomicInteger();
        AtomicInteger loginPosts = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                pageHits.incrementAndGet();
                StubServer.respond(exchange, 200, "text/html", "<html><body>Sign in</body></html>");
            } else {
                loginPosts.incrementAndGet();
                StubServer.respond(exchange, 200, "text/html", "");
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), USERS);

            AuthContext first = auth.authenticate("admin");
            AuthContext second = auth.authenticate("admin");

            assertEquals("rails auth unavailable (CSRF token missing)", first.note());
            assertEquals("rails auth unavailable (CSRF token missing)", second.note());
            assertNull(first.csrfToken());
            assertNull(first.authorization());
            assertEquals(2, pageHits.get());
            assertEquals(0, loginPosts.get());
        }
    }

    @Test
    void rejectedLoginsAreUnavailableWhetherRailsRerendersOrRedirectsBackToSignIn() throws Exception {
        AtomicReference<String> redirect = new AtomicReference<>(null);
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                StubServer.respond(exchange, 200, "text/html", META_TOKEN_PAGE);
            } else if (redirect.get() == null) {
                StubServer.respond(exchange, 200, "text/html", "<html>Invalid email or password.</html>");
            } else {
                exchange.getResponseHeaders().add("Location", redirect.get());
                StubServer.respond(exchange, 302, "text/html", "");
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), USERS);

            assertEquals("rails auth unavailable (login returned 200)", auth.authenticate("admin").note());

            redirect.set(server.url() + "/users/sign_in");
            AuthContext suspended = auth.authenticate("admin");
            assertEquals("rails auth unavailable (login returned 302)", suspended.note());
            assertNull(suspended.csrfToken());
        }
    }

    @Test
    void missingPostLoginTokenIsUnavailableAndRetriesNextTime() throws Exception {
        AtomicInteger loginPosts = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/users/sign_in") && exchange.getRequestMethod().equals("GET")) {
                StubServer.respond(exchange, 200, "text/html", META_TOKEN_PAGE);
            } else if (path.equals("/users/sign_in")) {
                loginPosts.incrementAndGet();
                exchange.getResponseHeaders().add("Location", "/");
                StubServer.respond(exchange, 302, "text/html", "");
            } else {
                StubServer.respond(exchange, 200, "text/html", "<html><body>Dashboard without a token</body></html>");
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), USERS);

            assertEquals("rails auth unavailable (post-login CSRF token missing)", auth.authenticate("admin").note());
            assertEquals("rails auth unavailable (post-login CSRF token missing)", auth.authenticate("admin").note());
            assertEquals(2, loginPosts.get());
        }
    }

    @Test
    void fallsBackToTheHiddenInputTokenAndSubmitsTheFixtureCredentials() throws Exception {
        AtomicReference<String> loginContentType = new AtomicReference<>();
        AtomicReference<String> loginBody = new AtomicReference<>();
        AtomicInteger loginPosts = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/users/sign_in") && exchange.getRequestMethod().equals("GET")) {
                StubServer.respond(exchange, 200, "text/html", "<form><input type=\"hidden\" "
                    + "name=\"authenticity_token\" value=\"input-token\" /></form>");
            } else if (path.equals("/users/sign_in")) {
                loginPosts.incrementAndGet();
                loginContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                loginBody.set(read(exchange));
                exchange.getResponseHeaders().add("Location", "/");
                StubServer.respond(exchange, 302, "text/html", "");
            } else {
                StubServer.respond(exchange, 200, "text/html",
                    "<META NAME='csrf-token' CONTENT='home-token'>");
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), USERS);

            AuthContext context = auth.authenticate("admin");

            assertEquals("application/x-www-form-urlencoded", loginContentType.get());
            Map<String, String> form = decode(loginBody.get());
            assertEquals("input-token", form.get("authenticity_token"));
            assertEquals("admin@contract.example", form.get("user[email]"));
            assertEquals("contract-password", form.get("user[password]"));
            assertEquals("home-token", context.csrfToken());
            assertNull(context.authorization());
            assertNull(context.note());
            assertSame(context, auth.authenticate("admin"));
            assertEquals(1, loginPosts.get());
        }
    }

    private static Map<String, String> decode(String form) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : form.split("&")) {
            String[] parts = pair.split("=", 2);
            fields.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        assertTrue(fields.size() >= 3, form);
        return fields;
    }

    private static String read(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
