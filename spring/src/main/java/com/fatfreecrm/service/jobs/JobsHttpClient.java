package com.fatfreecrm.service.jobs;

import com.fatfreecrm.config.JobsProperties;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class JobsHttpClient {

    private final HttpClient client;
    private final JobsProperties.Http properties;

    public JobsHttpClient(JobsProperties properties) {
        this.properties = properties.getHttp();
        this.client = HttpClient.newBuilder()
            .connectTimeout(this.properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @PostConstruct
    void validateProperties() {
        if (this.properties.getMaxBodyBytes() <= 0) {
            throw new IllegalStateException("ffcrm.jobs.http.max-body-bytes must be positive");
        }
    }

    public Response get(URI uri, String accept, String userAgent) throws IOException, InterruptedException {
        validate(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(properties.getReadTimeout())
            .header("Accept", accept)
            .header("User-Agent", userAgent)
            .GET()
            .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            return new Response(response.statusCode(), readBounded(body, properties.getMaxBodyBytes()));
        }
    }

    public Response postForm(URI uri, String body, String accept, String userAgent)
        throws IOException, InterruptedException {
        validate(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(properties.getReadTimeout())
            .header("Accept", accept)
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream responseBody = response.body()) {
            return new Response(response.statusCode(), readBounded(responseBody, properties.getMaxBodyBytes()));
        }
    }

    private void validate(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported");
        }
        if (properties.isBlockPrivateAddresses()) {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                    throw new IOException("Private network address blocked");
                }
            }
        }
    }

    private static byte[] readBounded(InputStream input, long maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > maximum) {
                throw new IOException("HTTP response exceeded configured body limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public record Response(int statusCode, byte[] body) {

        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
