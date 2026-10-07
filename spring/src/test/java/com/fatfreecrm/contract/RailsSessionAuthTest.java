package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class RailsSessionAuthTest {
    @Test
    void unknownUserIsRejectedBeforeAnyRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            requests.incrementAndGet();
            StubServer.respond(exchange, 200, "text/html", signInPage("tok"));
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("alice", user("alice")));
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> auth.authenticate("ghost"));
            assertEquals("Unknown contract fixture user: ghost", exception.getMessage());
            assertEquals(0, requests.get());
        }
    }

    @Test
    void unreachableSignInPageStatusesAreIoErrors() throws Exception {
        for (int status : new int[] {404, 500, 503}) {
            try (StubServer server = new StubServer(
                exchange -> StubServer.respond(exchange, status, "text/html", "x"))) {
                RailsSessionAuth auth = new RailsSessionAuth(server.url() + "/", Map.of("alice", user("alice")));
                IOException exception = assertThrows(IOException.class, () -> auth.authenticate("alice"));
                assertEquals("Rails sign-in page is unreachable (HTTP " + status + ") at " + server.url()
                    + "/users/sign_in", exception.getMessage());
            }
        }
    }

    @Test
    void connectionRefusedIsWrappedWithTheSignInUrl() throws Exception {
        String unreachable;
        try (StubServer server = new StubServer(exchange -> StubServer.respond(exchange, 200, "text/html", ""))) {
            unreachable = server.url();
        }
        RailsSessionAuth auth = new RailsSessionAuth(unreachable, Map.of("alice", user("alice")));
        IOException exception = assertThrows(IOException.class, () -> auth.authenticate("alice"));
        assertEquals("Rails sign-in page is unreachable at " + unreachable + "/users/sign_in",
            exception.getMessage());
        assertNotNull(exception.getCause());
    }

    @Test
    void missingCsrfTokenIsUnavailableAndNotCached() throws Exception {
        AtomicInteger pageRequests = new AtomicInteger();
        AtomicInteger loginRequests = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                pageRequests.incrementAndGet();
                StubServer.respond(exchange, 200, "text/html", "<html><form></form></html>");
            } else {
                loginRequests.incrementAndGet();
                StubServer.respond(exchange, 200, "text/html", "");
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("alice", user("alice")));
            AuthContext first = auth.authenticate("alice");
            assertEquals("rails auth unavailable (CSRF token missing)", first.note());
            assertNull(first.csrfToken());
            assertNull(first.authorization());
            assertNotNull(first.client());
            AuthContext second = auth.authenticate("alice");
            assertNotSame(first, second);
            assertEquals(2, pageRequests.get());
            assertEquals(0, loginRequests.get());
        }
    }

    @Test
    void rejectedCredentialsAreReportedWithTheLoginStatusAndNotCached() throws Exception {
        assertEquals("rails auth unavailable (login returned 200)",
            loginNote(exchange -> StubServer.respond(exchange, 200, "text/html", signInPage("tok"))));
        assertEquals("rails auth unavailable (login returned 302)",
            loginNote(exchange -> redirect(exchange, "/users/sign_in")));
        assertEquals("rails auth unavailable (login returned 422)",
            loginNote(exchange -> StubServer.respond(exchange, 422, "text/html", "")));
    }

    @Test
    void redirectWithoutPostLoginTokenIsUnavailable() throws Exception {
        try (StubServer server = new StubServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/users/sign_in") && exchange.getRequestMethod().equals("GET")) {
                StubServer.respond(exchange, 200, "text/html", signInPage("tok"));
            } else if (path.equals("/users/sign_in")) {
                redirect(exchange, "/");
            } else {
                StubServer.respond(exchange, 200, "text/html", "<html>no meta</html>");
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("alice", user("alice")));
            AuthContext context = auth.authenticate("alice");
            assertEquals("rails auth unavailable (post-login CSRF token missing)", context.note());
            assertNull(context.csrfToken());
            assertNotSame(context, auth.authenticate("alice"));
        }
    }

    @Test
    void scrapesTokensFromInputOrMetaTagsAndIsolatesSessionsPerUser() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        List<String> loginBodies = Collections.synchronizedList(new ArrayList<>());
        try (StubServer server = new StubServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/users/sign_in") && exchange.getRequestMethod().equals("GET")) {
                StubServer.respond(exchange, 200, "text/html",
                    "<form><input type='hidden' name='authenticity_token' value='input-token'></form>");
            } else if (path.equals("/users/sign_in")) {
                logins.incrementAndGet();
                loginBodies.add(readBody(exchange));
                redirect(exchange, "/");
            } else {
                StubServer.respond(exchange, 200, "text/html", "<META NAME='csrf-token' CONTENT='home-token'>");
            }
        })) {
            Map<String, FixtureUsers.FixtureUser> users = Map.of("alice", user("alice"), "bob", user("bob"));
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), users);
            AuthContext alice = auth.authenticate("alice");
            AuthContext bob = auth.authenticate("bob");
            assertEquals("home-token", alice.csrfToken());
            assertEquals("home-token", bob.csrfToken());
            assertNull(alice.authorization());
            assertNull(alice.note());
            assertNotSame(alice.client(), bob.client());
            assertSame(alice, auth.authenticate("alice"));
            assertSame(bob, auth.authenticate("bob"));
            assertEquals(2, logins.get());
            assertTrue(loginBodies.get(0).contains("authenticity_token=input-token"));
            assertTrue(loginBodies.get(0).contains("user%5Bemail%5D=alice%40contract.example"));
            assertTrue(loginBodies.get(1).contains("user%5Bemail%5D=bob%40contract.example"));
            assertTrue(loginBodies.stream().allMatch(body -> body.contains("user%5Bpassword%5D=contract-password")));
        }
    }

    private static String loginNote(Consumer<HttpExchange> loginHandler) throws Exception {
        AtomicInteger pageRequests = new AtomicInteger();
        try (StubServer server = new StubServer(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                pageRequests.incrementAndGet();
                StubServer.respond(exchange, 200, "text/html", signInPage("tok"));
            } else {
                loginHandler.accept(exchange);
            }
        })) {
            RailsSessionAuth auth = new RailsSessionAuth(server.url(), Map.of("alice", user("alice")));
            AuthContext context = auth.authenticate("alice");
            assertNull(context.csrfToken());
            assertNull(context.authorization());
            assertNotSame(context, auth.authenticate("alice"));
            assertEquals(2, pageRequests.get());
            return context.note();
        }
    }

    private static String signInPage(String token) {
        return "<meta name=\"csrf-token\" content=\"" + token + "\">";
    }

    private static void redirect(HttpExchange exchange, String location) {
        try {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static FixtureUsers.FixtureUser user(String key) {
        return new FixtureUsers.FixtureUser(key, 2, key, key + "@contract.example", "contract-password", false,
            false);
    }
}
