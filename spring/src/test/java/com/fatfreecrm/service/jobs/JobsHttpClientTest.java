package com.fatfreecrm.service.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.config.JobsProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Edge cases of the outbound HTTP client the website and Wikidata jobs share. */
class JobsHttpClientTest {

    private HttpServer server;
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private boolean targetHit;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            record(exchange);
            respond(exchange, 200, "echo-body");
        });
        server.createContext("/large", exchange -> respond(exchange, 200, "x".repeat(100)));
        server.createContext("/unavailable", exchange -> respond(exchange, 503, "unavailable"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", url("/target"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHit = true;
            respond(exchange, 200, "target");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getSendsAcceptAndUserAgentAndReturnsStatusWithBody() throws Exception {
        JobsHttpClient.Response response = client(new JobsProperties())
            .get(URI.create(url("/echo")), "text/html", "FatFreeCRM/Spring");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("echo-body");
        assertThat(method.get()).isEqualTo("GET");
        assertThat(accept.get()).isEqualTo("text/html");
        assertThat(userAgent.get()).isEqualTo("FatFreeCRM/Spring");
        assertThat(contentType.get()).isNull();
    }

    @Test
    void postFormSendsUrlEncodedBody() throws Exception {
        JobsHttpClient.Response response = client(new JobsProperties())
            .postForm(URI.create(url("/echo")), "query=SELECT", "application/sparql-results+json", "FatFreeCRM");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(contentType.get()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(accept.get()).isEqualTo("application/sparql-results+json");
        assertThat(requestBody.get()).isEqualTo("query=SELECT");
    }

    @Test
    void nonSuccessStatusesAreReturnedNotThrown() throws Exception {
        JobsHttpClient.Response response = client(new JobsProperties())
            .get(URI.create(url("/unavailable")), "*/*", "ua");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("unavailable");
    }

    @Test
    void redirectsAreNeverFollowed() throws Exception {
        JobsHttpClient.Response response = client(new JobsProperties())
            .get(URI.create(url("/redirect")), "*/*", "ua");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(targetHit).isFalse();
    }

    @Test
    void bodiesLargerThanTheConfiguredLimitAreRejected() {
        JobsProperties properties = new JobsProperties();
        properties.getHttp().setMaxBodyBytes(10);

        assertThatThrownBy(() -> client(properties).get(URI.create(url("/large")), "*/*", "ua"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("exceeded configured body limit");
    }

    @Test
    void bodyExactlyAtTheLimitIsAccepted() throws Exception {
        JobsProperties properties = new JobsProperties();
        properties.getHttp().setMaxBodyBytes(100);

        JobsHttpClient.Response response = client(properties).get(URI.create(url("/large")), "*/*", "ua");
        assertThat(response.body()).hasSize(100);
    }

    @Test
    void onlyHttpAndHttpsSchemesAreAllowed() {
        JobsHttpClient client = client(new JobsProperties());
        assertThatThrownBy(() -> client.get(URI.create("ftp://example.test/file"), "*/*", "ua"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only HTTP and HTTPS");
        assertThatThrownBy(() -> client.postForm(URI.create("file:///etc/passwd"), "", "*/*", "ua"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.get(null, "*/*", "ua")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void privateAddressesAreBlockedOnlyWhenConfigured() throws Exception {
        JobsProperties blocking = new JobsProperties();
        blocking.getHttp().setBlockPrivateAddresses(true);
        assertThatThrownBy(() -> client(blocking).get(URI.create(url("/echo")), "*/*", "ua"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Private network address blocked");
        assertThat(method.get()).isNull();

        assertThat(client(new JobsProperties()).get(URI.create(url("/echo")), "*/*", "ua").statusCode())
            .isEqualTo(200);
    }

    @Test
    void nonPositiveBodyLimitFailsPropertyValidation() {
        JobsProperties properties = new JobsProperties();
        properties.getHttp().setMaxBodyBytes(0);
        assertThatThrownBy(() -> client(properties).validateProperties())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("max-body-bytes");

        JobsProperties valid = new JobsProperties();
        client(valid).validateProperties();
    }

    @Test
    void responseBodyIsDefensivelyCopied() {
        byte[] original = {1, 2, 3};
        JobsHttpClient.Response response = new JobsHttpClient.Response(200, original);
        original[0] = 9;
        byte[] first = response.body();
        first[1] = 9;
        assertThat(response.body()).containsExactly(1, 2, 3);
    }

    private static JobsHttpClient client(JobsProperties properties) {
        properties.getHttp().setReadTimeout(Duration.ofSeconds(5));
        return new JobsHttpClient(properties);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void record(HttpExchange exchange) throws IOException {
        method.set(exchange.getRequestMethod());
        accept.set(exchange.getRequestHeaders().getFirst("Accept"));
        userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
