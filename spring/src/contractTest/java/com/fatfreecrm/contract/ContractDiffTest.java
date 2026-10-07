package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractDiffTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final List<CaseResult> results = new ArrayList<>();
    private Allowlist allowlist;
    private String railsUrl;
    private String springUrl;
    private String railsUnavailable;

    @Test
    void railsSignInPageIsReachable() {
        String baseUrl = railsUrl == null
            ? System.getProperty("contract.railsUrl", "http://localhost:3000") : railsUrl;
        String unavailable = checkRailsSignIn(baseUrl);
        if (unavailable != null) {
            throw new AssertionError("Rails sign-in page is unreachable: " + unavailable);
        }
    }

    @TestFactory
    Stream<DynamicTest> contractCases() throws Exception {
        railsUrl = System.getProperty("contract.railsUrl", "http://localhost:3000");
        springUrl = System.getProperty("contract.springUrl", "http://localhost:8080");
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        List<ContractCase> cases = filter(CaseLoader.load(),
            System.getProperty("contract.caseFilter", ""));
        allowlist = Allowlist.load();
        JsonNode globalNormalize = loadNormalize();
        ContractClient client = new ContractClient(railsUrl, springUrl, users);
        railsUnavailable = checkRailsSignIn(railsUrl);
        ContractDiffer differ = new ContractDiffer();
        for (ContractCase contractCase : cases) {
            if (railsUnavailable != null) {
                results.add(error(contractCase, railsUnavailable));
                continue;
            }
            try {
                ContractClient.RequestResult rails = client.send(contractCase, true);
                ContractClient.RequestResult spring = client.send(contractCase, false);
                List<String> notes = new ArrayList<>(rails.notes());
                notes.addAll(spring.notes());
                results.add(differ.diff(contractCase, rails.response(), spring.response(), allowlist,
                    globalNormalize, rails.url(), spring.url(), notes, spring.authenticated())
                    .failIfEnforcedAuthUnavailable());
            } catch (Exception exception) {
                results.add(error(contractCase, exception.getClass().getSimpleName() + ": " + exception.getMessage()));
            }
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (CaseResult result : results) {
            tests.add(DynamicTest.dynamicTest(result.contractCase().id(), () -> {
                if (result.enforcedFailure()) {
                    throw new AssertionError((railsUnavailable == null ? "Enforced contract case "
                        : "Rails sign-in page is unreachable; enforced contract case ")
                        + result.contractCase().id() + " was " + result.outcome()
                        + (result.error() == null ? "" : ": " + result.error()));
                }
            }));
        }
        return tests.stream();
    }

    @AfterAll
    void writeReport() throws IOException {
        if (allowlist != null) {
            ReportWriter.write(Path.of(System.getProperty("contract.reportDir", "build/reports/contract-diff")),
                railsUrl, springUrl, results, allowlist);
        }
    }

    private static JsonNode loadNormalize() throws IOException {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream("contract/config.yml")) {
            if (input == null) {
                return YAML.createObjectNode();
            }
            return YAML.readTree(input).path("normalize");
        }
    }

    private static String checkRailsSignIn(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/users/sign_in"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 500 && response.statusCode() != 404) {
                return null;
            }
            return "GET /users/sign_in returned HTTP " + response.statusCode();
        } catch (Exception exception) {
            return "GET /users/sign_in failed: " + exception.getMessage();
        }
    }

    private static List<ContractCase> filter(List<ContractCase> cases, String regex) {
        if (regex.isBlank()) {
            return cases;
        }
        Pattern filter = Pattern.compile(regex);
        return cases.stream().filter(contractCase -> filter.matcher(contractCase.id()).find()).toList();
    }

    private static CaseResult error(ContractCase contractCase, String message) {
        return new CaseResult(contractCase, CaseResult.Outcome.ERROR, null, null, null, null, List.of(), List.of(),
            message);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
