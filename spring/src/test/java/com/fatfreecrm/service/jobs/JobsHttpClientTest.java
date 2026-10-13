package com.fatfreecrm.service.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.config.JobsProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Edge/error semantics of the hardened HTTP client used by the website/Wikidata enrichment jobs. */
class JobsHttpClientTest {

    private HttpServer server;
    private String base;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastAccept = new AtomicReference<>();
    private final AtomicReference<String> lastUserAgent = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "echoed");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/echo");
            respond(exchange, 302, "");
        });
        server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat(2048)));
        server.createContext("/missing", exchange -> respond(exchange, 404, "nope"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static JobsHttpClient client(java.util.function.Consumer<JobsProperties.Http> customize) {
        JobsProperties properties = new JobsProperties();
        customize.accept(properties.getHttp());
        JobsHttpClient client = new JobsHttpClient(properties);
        client.validateProperties();
        return client;
    }

    @Test
    void getSendsAcceptAndUserAgentAndReturnsStatusWithBody() throws Exception {
        JobsHttpClient.Response response =
            client(http -> { }).get(URI.create(base + "/echo"), "application/json", "FFCRM-test/1.0");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("echoed");
        assertThat(lastMethod.get()).isEqualTo("GET");
        assertThat(lastAccept.get()).isEqualTo("application/json");
        assertThat(lastUserAgent.get()).isEqualTo("FFCRM-test/1.0");
    }

    @Test
    void postFormSendsUrlEncodedBody() throws Exception {
        client(http -> { }).postForm(URI.create(base + "/echo"), "a=1&b=two", "*/*", "FFCRM-test/1.0");

        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(lastBody.get()).isEqualTo("a=1&b=two");
    }

    @Test
    void nonSuccessStatusesAreReturnedNotThrown() throws Exception {
        JobsHttpClient.Response response = client(http -> { }).get(URI.create(base + "/missing"), "*/*", "ua");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("nope");
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        JobsHttpClient.Response response = client(http -> { }).get(URI.create(base + "/redirect"), "*/*", "ua");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(lastMethod.get()).isNull();
    }

    @Test
    void onlyHttpAndHttpsSchemesAreAllowed() {
        JobsHttpClient client = client(http -> { });

        assertThatThrownBy(() -> client.get(URI.create("ftp://example.test/file"), "*/*", "ua"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Only HTTP and HTTPS URLs are supported");
        assertThatThrownBy(() -> client.get(URI.create("file:///etc/passwd"), "*/*", "ua"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get(null, "*/*", "ua")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void privateAddressesAreBlockedOnlyWhenConfigured() throws Exception {
        URI loopback = URI.create(base + "/echo");

        assertThat(client(http -> { }).get(loopback, "*/*", "ua").statusCode()).isEqualTo(200);

        JobsHttpClient blocking = client(http -> http.setBlockPrivateAddresses(true));
        assertThatThrownBy(() -> blocking.get(loopback, "*/*", "ua"))
            .isInstanceOf(IOException.class)
            .hasMessage("Private network address blocked");
        assertThatThrownBy(() -> blocking.postForm(loopback, "a=1", "*/*", "ua"))
            .isInstanceOf(IOException.class)
            .hasMessage("Private network address blocked");
    }

    @Test
    void responsesLargerThanTheConfiguredLimitAreRejected() throws Exception {
        JobsHttpClient bounded = client(http -> http.setMaxBodyBytes(1024));

        assertThatThrownBy(() -> bounded.get(URI.create(base + "/large"), "*/*", "ua"))
            .isInstanceOf(IOException.class)
            .hasMessage("HTTP response exceeded configured body limit");

        JobsHttpClient exact = client(http -> http.setMaxBodyBytes(2048));
        assertThat(exact.get(URI.create(base + "/large"), "*/*", "ua").body()).hasSize(2048);
    }

    @Test
    void nonPositiveBodyLimitFailsStartupValidation() {
        assertThatThrownBy(() -> client(http -> http.setMaxBodyBytes(0)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-body-bytes");
    }

    @Test
    void responseBodyIsDefensivelyCopied() {
        byte[] original = {1, 2, 3};
        JobsHttpClient.Response response = new JobsHttpClient.Response(200, original);
        original[0] = 9;
        byte[] exposed = response.body();
        exposed[1] = 9;

        assertThat(response.body()).containsExactly(1, 2, 3);
    }
}
