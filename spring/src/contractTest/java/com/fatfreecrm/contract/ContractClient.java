package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ContractClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String railsUrl;
    private final String springUrl;
    private final AuthAdapter railsAuth;
    private final AuthAdapter springAuth;
    private final HttpClient anonymousClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    public ContractClient(String railsUrl, String springUrl, Map<String, FixtureUsers.FixtureUser> users) {
        this.railsUrl = trimSlash(railsUrl);
        this.springUrl = trimSlash(springUrl);
        this.railsAuth = new RailsSessionAuth(this.railsUrl, users);
        this.springAuth = new SpringJwtAuth(this.springUrl, users);
    }

    public RequestResult send(ContractCase contractCase, boolean railsSide)
        throws IOException, InterruptedException {
        ContractCase.SideRequest side = railsSide ? contractCase.rails() : contractCase.spring();
        String targetBase = side.target() == ContractCase.Target.RAILS ? railsUrl : springUrl;
        String requestUrl = targetBase + side.path() + query(contractCase.params());
        HttpClient client = anonymousClient;
        String authorization = null;
        AuthContext authContext = null;
        boolean authenticated = false;
        List<String> notes = new ArrayList<>();
        if (!contractCase.auth().equals("anonymous")) {
            AuthAdapter adapter = side.target() == ContractCase.Target.RAILS ? railsAuth : springAuth;
            try {
                authContext = adapter.authenticate(contractCase.auth());
            } catch (IOException | InterruptedException exception) {
                throw new IOException("Authentication failed for fixture user " + contractCase.auth()
                    + " via " + side.target() + " for " + requestUrl + ": " + describe(exception), exception);
            }
            client = authContext.client();
            authorization = authContext.authorization();
            authenticated = authorization != null || authContext.csrfToken() != null;
            if (authContext.note() != null) {
                notes.add((side.target() == ContractCase.Target.RAILS ? "rails" : "spring") + " "
                    + authContext.note().replaceFirst("^(rails|spring) ", ""));
            }
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(requestUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (authContext != null && side.target() == ContractCase.Target.RAILS
            && !contractCase.method().equalsIgnoreCase("GET")
            && !contractCase.method().equalsIgnoreCase("HEAD") && authContext.csrfToken() != null) {
            request.header("X-CSRF-Token", authContext.csrfToken());
        }
        if (contractCase.body() != null && !contractCase.body().isNull()) {
            request.header("Content-Type", "application/json")
                .method(contractCase.method(), HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(contractCase.body()), StandardCharsets.UTF_8));
        } else {
            request.method(contractCase.method(), HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException exception) {
            throw new IOException("Request failed: " + contractCase.method() + " " + requestUrl, exception);
        }
        return new RequestResult(requestUrl, CapturedResponse.from(response), List.copyOf(notes), authenticated);
    }

    private static String query(JsonNode params) {
        List<String> values = new ArrayList<>();
        if (params != null && params.isObject()) {
            params.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (value.isArray()) {
                    value.forEach(item -> values.add(encode(entry.getKey()) + "=" + encode(item.asText())));
                } else {
                    values.add(encode(entry.getKey()) + "=" + encode(value.asText()));
                }
            });
        }
        return values.isEmpty() ? "" : "?" + String.join("&", values);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String describe(Exception exception) {
        return exception.getClass().getSimpleName()
            + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record RequestResult(String url, CapturedResponse response, List<String> notes, boolean authenticated) {
        public RequestResult {
            notes = List.copyOf(notes);
        }
    }
}
