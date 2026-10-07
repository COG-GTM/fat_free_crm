package com.fatfreecrm.contract;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RailsSessionAuth implements AuthAdapter {
    private static final Pattern META_TOKEN = Pattern.compile(
        "<meta[^>]+name=[\"']csrf-token[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_TOKEN = Pattern.compile(
        "<input[^>]+name=[\"']authenticity_token[\"'][^>]+value=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private final String baseUrl;
    private final Map<String, FixtureUsers.FixtureUser> users;
    private final Map<String, AuthContext> sessions = new ConcurrentHashMap<>();

    public RailsSessionAuth(String baseUrl, Map<String, FixtureUsers.FixtureUser> users) {
        this.baseUrl = trimSlash(baseUrl);
        this.users = Map.copyOf(users);
    }

    @Override
    public AuthContext authenticate(String userKey) throws IOException, InterruptedException {
        AuthContext cached = sessions.get(userKey);
        if (cached != null) {
            return cached;
        }
        FixtureUsers.FixtureUser user = users.get(userKey);
        if (user == null) {
            throw new IllegalArgumentException("Unknown contract fixture user: " + userKey);
        }
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpResponse<String> page;
        try {
            page = send(client, HttpRequest.newBuilder(URI.create(baseUrl + "/users/sign_in"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build());
        } catch (IOException exception) {
            throw new IOException("Rails sign-in page is unreachable at " + baseUrl + "/users/sign_in", exception);
        }
        if (page.statusCode() >= 500 || page.statusCode() == 404) {
            throw new IOException("Rails sign-in page is unreachable (HTTP " + page.statusCode() + ") at "
                + baseUrl + "/users/sign_in");
        }
        String token = token(page.body());
        if (token == null) {
            return unavailable(client, "rails auth unavailable (CSRF token missing)");
        }
        String form = form(Map.of(
            "user[email]", user.email(),
            "user[password]", user.password(),
            "authenticity_token", token
        ));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/users/sign_in"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "text/html")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        HttpResponse<String> login = send(client, request);
        String location = login.headers().firstValue("Location").orElse("");
        if (login.statusCode() >= 300 && login.statusCode() < 400
            && !location.contains("/users/sign_in")) {
            HttpRequest homeRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/html")
                .GET()
                .build();
            HttpResponse<String> home = send(client, homeRequest);
            String postLoginToken = token(home.body());
            if (postLoginToken == null) {
                return unavailable(client, "rails auth unavailable (post-login CSRF token missing)");
            }
            AuthContext result = new AuthContext(client, null, postLoginToken, null);
            sessions.put(userKey, result);
            return result;
        }
        return unavailable(client, "rails auth unavailable (login returned " + login.statusCode() + ")");
    }

    private static AuthContext unavailable(HttpClient client, String note) {
        return new AuthContext(client, null, null, note);
    }

    private static String token(String html) {
        for (Pattern pattern : new Pattern[] {META_TOKEN, INPUT_TOKEN}) {
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request)
        throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
