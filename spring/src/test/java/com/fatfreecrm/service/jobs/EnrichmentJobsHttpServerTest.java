package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class EnrichmentJobsHttpServerTest {

    @Test
    void enrichesAnAccountFromWebsiteJsonLd() throws Exception {
        String jsonLd = """
            <script type="application/ld+json">
            {"@type":"Organization","telephone":"+1-555-0100","email":"info@example.test",
             "geo":{"@type":"GeoCoordinates","latitude":41.5,"longitude":-72.25},
             "address":{"@type":"PostalAddress","streetAddress":"10 Main St","addressLocality":"Hartford"}}
            </script>
            """;
        HttpServer server = server("/site", jsonLd, null);
        try {
            JobsProperties properties = new JobsProperties();
            Account account = new Account();
            ReflectionTestUtils.setField(account, "id", 51L);
            account.setWebsite("http://127.0.0.1:" + server.getAddress().getPort() + "/site");
            account.setWikidataId("Q42");
            AccountRepository accounts = mock(AccountRepository.class);
            when(accounts.findById(51L)).thenReturn(Optional.of(account));
            AddressRepository addresses = mock(AddressRepository.class);
            when(addresses.findByAddressableTypeAndAddressableId("Account", 51)).thenReturn(List.of());
            AccountEnrichmentTrigger trigger = mock(AccountEnrichmentTrigger.class);
            AccountWebsiteJob job = new AccountWebsiteJob();
            ReflectionTestUtils.setField(job, "jobsOwner", springOwner());
            ReflectionTestUtils.setField(job, "httpClient", new JobsHttpClient(properties));
            ReflectionTestUtils.setField(job, "accountRepository", accounts);
            ReflectionTestUtils.setField(job, "addressRepository", addresses);
            ReflectionTestUtils.setField(job, "objectMapper", new ObjectMapper());
            ReflectionTestUtils.setField(job, "enrichmentTrigger", trigger);

            job.perform(51L);

            assertEquals("+1-555-0100", account.getPhone());
            assertEquals("info@example.test", account.getEmail());
            assertEquals("41.5", account.getLatitude().toPlainString());
            assertEquals("-72.25", account.getLongitude().toPlainString());
            verify(accounts).save(account);
            verify(addresses).save(org.mockito.ArgumentMatchers.argThat(address ->
                "10 Main St".equals(address.getStreet1()) && "Hartford".equals(address.getCity())));
            verify(trigger).afterAccountSaved(51L, false, true);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enrichesAnAccountFromWikidataSparqlResults() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String result = """
            {"results":{"bindings":[{"description":{"value":"A useful account"},
             "website":{"value":"https://example.test"},"twitter":{"value":"example"},
             "linkedin":{"value":"example-company"}}]}}
            """;
        HttpServer server = server("/sparql", result, exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        });
        try {
            JobsProperties properties = new JobsProperties();
            properties.getWikidata().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/sparql");
            Account account = new Account();
            ReflectionTestUtils.setField(account, "id", 52L);
            account.setWikidataId("Q42");
            AccountRepository accounts = mock(AccountRepository.class);
            when(accounts.findById(52L)).thenReturn(Optional.of(account));
            AccountEnrichmentTrigger trigger = mock(AccountEnrichmentTrigger.class);
            WikidataJob job = new WikidataJob();
            ReflectionTestUtils.setField(job, "jobsOwner", springOwner());
            ReflectionTestUtils.setField(job, "httpClient", new JobsHttpClient(properties));
            ReflectionTestUtils.setField(job, "properties", properties);
            ReflectionTestUtils.setField(job, "accountRepository", accounts);
            ReflectionTestUtils.setField(job, "objectMapper", new ObjectMapper());
            ReflectionTestUtils.setField(job, "enrichmentTrigger", trigger);

            job.perform(52L);

            assertEquals("A useful account", account.getBackgroundInfo());
            assertEquals("https://example.test", account.getWebsite());
            assertEquals("https://twitter.com/example", account.getTwitter());
            assertEquals("https://www.linkedin.com/company/example-company", account.getLinkedin());
            assertEquals("application/x-www-form-urlencoded", contentType.get());
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("query="));
            verify(accounts).save(account);
            verify(trigger).websiteChanged(52L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ignoresNonSuccessAndInvalidWebsiteResponsesWithoutFollowingRedirects() throws Exception {
        AtomicReference<Boolean> redirectTargetHit = new AtomicReference<>(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectTargetHit.set(true);
            respond(exchange, 200, "<script type=\"application/ld+json\">"
                + "{\"@type\":\"Organization\",\"telephone\":\"+1-555-0100\"}</script>");
        });
        server.createContext("/missing", exchange -> respond(exchange, 503, "unavailable"));
        server.createContext("/invalid", exchange -> respond(exchange, 200,
            "<script type=\"application/ld+json\">invalid json</script>"));
        server.start();
        try {
            JobsProperties properties = new JobsProperties();
            Account account = new Account();
            ReflectionTestUtils.setField(account, "id", 53L);
            AccountRepository accounts = mock(AccountRepository.class);
            when(accounts.findById(53L)).thenReturn(Optional.of(account));
            AddressRepository addresses = mock(AddressRepository.class);
            AccountEnrichmentTrigger trigger = mock(AccountEnrichmentTrigger.class);
            AccountWebsiteJob job = websiteJob(properties, accounts, addresses, trigger);

            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            account.setWebsite(origin + "/redirect");
            job.perform(53L);
            assertFalse(redirectTargetHit.get());
            account.setWebsite(origin + "/missing");
            job.perform(53L);
            account.setWebsite(origin + "/invalid");
            job.perform(53L);

            org.junit.jupiter.api.Assertions.assertNull(account.getPhone());
            org.mockito.Mockito.verify(accounts, org.mockito.Mockito.never()).save(account);
            org.mockito.Mockito.verifyNoInteractions(trigger);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutSlowWebsiteResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(400);
                respond(exchange, 200, "{}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            JobsProperties properties = new JobsProperties();
            properties.getHttp().setReadTimeout(Duration.ofMillis(100));
            Account account = new Account();
            ReflectionTestUtils.setField(account, "id", 54L);
            account.setWebsite("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
            AccountRepository accounts = mock(AccountRepository.class);
            when(accounts.findById(54L)).thenReturn(Optional.of(account));

            assertThrows(HttpTimeoutException.class,
                () -> websiteJob(properties, accounts, mock(AddressRepository.class),
                    mock(AccountEnrichmentTrigger.class)).perform(54L));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chainsWikidataWebsiteDiscoveryIntoWebsiteEnrichment() throws Exception {
        String[] originHolder = new String[1];
        String wikidata = """
            {"results":{"bindings":[{"website":{"value":"%s/site"}}]}}
            """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sparql", exchange -> {
            respond(exchange, 200, wikidata.formatted(originHolder[0]));
        });
        server.createContext("/site", exchange -> respond(exchange, 200,
            "<script type=\"application/ld+json\">"
                + "{\"@type\":\"Organization\",\"telephone\":\"+1-555-0100\"}</script>"));
        server.start();
        originHolder[0] = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            JobsProperties properties = new JobsProperties();
            properties.getWikidata().setEndpoint(originHolder[0] + "/sparql");
            Account account = new Account();
            ReflectionTestUtils.setField(account, "id", 55L);
            account.setWikidataId("Q42");
            AccountRepository accounts = mock(AccountRepository.class);
            when(accounts.findById(55L)).thenReturn(Optional.of(account));
            AddressRepository addresses = mock(AddressRepository.class);
            AccountEnrichmentTrigger trigger = mock(AccountEnrichmentTrigger.class);
            WikidataJob wikidataJob = new WikidataJob();
            ReflectionTestUtils.setField(wikidataJob, "jobsOwner", springOwner());
            ReflectionTestUtils.setField(wikidataJob, "httpClient", new JobsHttpClient(properties));
            ReflectionTestUtils.setField(wikidataJob, "properties", properties);
            ReflectionTestUtils.setField(wikidataJob, "accountRepository", accounts);
            ReflectionTestUtils.setField(wikidataJob, "objectMapper", new ObjectMapper());
            ReflectionTestUtils.setField(wikidataJob, "enrichmentTrigger", trigger);

            wikidataJob.perform(55L);
            verify(trigger).websiteChanged(55L);
            assertEquals(originHolder[0] + "/site", account.getWebsite());
            websiteJob(properties, accounts, addresses, trigger).perform(55L);

            assertEquals("+1-555-0100", account.getPhone());
        } finally {
            server.stop(0);
        }
    }

    private static AccountWebsiteJob websiteJob(
        JobsProperties properties,
        AccountRepository accounts,
        AddressRepository addresses,
        AccountEnrichmentTrigger trigger
    ) {
        AccountWebsiteJob job = new AccountWebsiteJob();
        ReflectionTestUtils.setField(job, "jobsOwner", springOwner());
        ReflectionTestUtils.setField(job, "httpClient", new JobsHttpClient(properties));
        ReflectionTestUtils.setField(job, "accountRepository", accounts);
        ReflectionTestUtils.setField(job, "addressRepository", addresses);
        ReflectionTestUtils.setField(job, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(job, "enrichmentTrigger", trigger);
        return job;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String response)
        throws java.io.IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static HttpServer server(String path, String response, RequestCapture capture) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            if (capture != null) {
                capture.capture(exchange);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static JobsOwner springOwner() {
        JobsProperties properties = new JobsProperties();
        properties.setOwner("spring");
        return new JobsOwner(properties);
    }

    @FunctionalInterface
    private interface RequestCapture {
        void capture(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException;
    }
}
