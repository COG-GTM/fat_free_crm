package com.fatfreecrm.service.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.config.JobsProperties;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.history.RailsRowAttributes;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WikidataJob implements Job {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikidataJob.class);
    private static final String QUERY = """
        SELECT ?description ?website ?address ?logo ?twitter ?linkedin ?instagram ?mastodon ?facebook ?bluesky
               ?blog WHERE {
          BIND(wd:%s AS ?item)
          OPTIONAL { ?item schema:description ?description . FILTER(LANG(?description) = "en") }
          OPTIONAL { ?item wdt:P856 ?website . }
          OPTIONAL { ?item wdt:P6375 ?address . }
          OPTIONAL { ?item wdt:P154 ?logo . }
          OPTIONAL {
            ?item p:P2002 ?twitter_statement .
            ?twitter_statement ps:P2002 ?twitter .
            FILTER NOT EXISTS { ?twitter_statement pq:P582 ?twitter_end_date }
          }
          OPTIONAL {
            ?item p:P4264 ?linkedin_statement .
            ?linkedin_statement ps:P4264 ?linkedin .
            FILTER NOT EXISTS { ?linkedin_statement pq:P582 ?linkedin_end_date }
          }
          OPTIONAL {
            ?item p:P2003 ?instagram_statement .
            ?instagram_statement ps:P2003 ?instagram .
            FILTER NOT EXISTS { ?instagram_statement pq:P582 ?instagram_end_date }
          }
          OPTIONAL {
            ?item p:P4033 ?mastodon_statement .
            ?mastodon_statement ps:P4033 ?mastodon .
            FILTER NOT EXISTS { ?mastodon_statement pq:P582 ?mastodon_end_date }
          }
          OPTIONAL {
            ?item p:P2013 ?facebook_statement .
            ?facebook_statement ps:P2013 ?facebook .
            FILTER NOT EXISTS { ?facebook_statement pq:P582 ?facebook_end_date }
          }
          OPTIONAL {
            ?item p:P12361 ?bluesky_statement .
            ?bluesky_statement ps:P12361 ?bluesky .
            FILTER NOT EXISTS { ?bluesky_statement pq:P582 ?bluesky_end_date }
          }
          OPTIONAL {
            ?item p:P1581 ?blog_statement .
            ?blog_statement ps:P1581 ?blog .
            FILTER NOT EXISTS { ?blog_statement pq:P582 ?blog_end_date }
          }
        }
        """;

    @Autowired
    private JobsOwner jobsOwner;
    @Autowired
    private JobsHttpClient httpClient;
    @Autowired
    private JobsProperties properties;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private VersionRecorder versionRecorder;
    @Autowired
    private RailsRowAttributes rowAttributes;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AccountEnrichmentTrigger enrichmentTrigger;

    @Override
    public void execute(JobExecutionContext context) {
        if (!jobsOwner.isSpring()) {
            LOGGER.warn("jobs owner is rails; skipping WikidataJob");
            return;
        }
        long accountId = context.getMergedJobDataMap().getLong("accountId");
        try {
            perform(accountId);
        } catch (Exception exception) {
            throw new IllegalStateException("Wikidata enrichment failed for account " + accountId, exception);
        }
    }

    @Transactional
    public void perform(long accountId) throws IOException, InterruptedException {
        if (!jobsOwner.isSpring()) {
            return;
        }
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null || blank(account.getWikidataId())) {
            return;
        }
        Map<String, Object> before = rowAttributes.read(account);
        String query = QUERY.replace("%s", account.getWikidataId());
        JobsHttpClient.Response response = httpClient.postForm(
            URI.create(properties.getWikidata().getEndpoint()),
            "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8),
            "application/sparql-results+json",
            "Fat Free CRM");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return;
        }
        JsonNode bindings = objectMapper.readTree(response.body()).path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            return;
        }
        JsonNode result = bindings.get(0);
        String previousWebsite = account.getWebsite();
        boolean changed = false;
        changed |= fill(account.getBackgroundInfo(), value(result, "description"), account::setBackgroundInfo);
        changed |= fill(account.getWebsite(), value(result, "website"), account::setWebsite);
        changed |= fill(
            account.getTwitter(),
            prefix("https://twitter.com/", value(result, "twitter")),
            account::setTwitter);
        changed |= fill(
            account.getLinkedin(),
            prefix("https://www.linkedin.com/company/", value(result, "linkedin")),
            account::setLinkedin);
        changed |= fill(account.getInstagram(), prefix("https://www.instagram.com/", value(result, "instagram")),
            account::setInstagram);
        String mastodon = value(result, "mastodon");
        if (!blank(mastodon) && blank(account.getMastodon())) {
            String mapped = mastodon.startsWith("http") ? mastodon
                : mastodon.matches("^@?[^@]+@.+$") ? "https://" + mastodon.replaceFirst("^@?([^@]+)@(.+)$",
                    "$2/@$1") : mastodon;
            account.setMastodon(mapped);
            changed = true;
        }
        changed |= fill(account.getFacebook(), prefix("https://www.facebook.com/", value(result, "facebook")),
            account::setFacebook);
        changed |= fill(account.getBluesky(), prefix("https://bsky.app/profile/", value(result, "bluesky")),
            account::setBluesky);
        changed |= fill(account.getBlog(), value(result, "blog"), account::setBlog);
        if (changed) {
            account.setUpdatedAt(Instant.now());
            accountRepository.saveAndFlush(account);
            versionRecorder.recordUpdate(null, account, before, rowAttributes.read(account));
        }
        if (blank(previousWebsite) && !blank(account.getWebsite())) {
            enrichmentTrigger.websiteChanged(accountId);
        }
    }

    private static String value(JsonNode result, String key) {
        JsonNode field = result.get(key);
        return field == null || field.isNull() ? null : field.path("value").asText(null);
    }

    private static String prefix(String prefix, String value) {
        return blank(value) ? null : prefix + value;
    }

    private static boolean fill(String current, String value, java.util.function.Consumer<String> setter) {
        if (blank(current) && !blank(value)) {
            setter.accept(value);
            return true;
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
