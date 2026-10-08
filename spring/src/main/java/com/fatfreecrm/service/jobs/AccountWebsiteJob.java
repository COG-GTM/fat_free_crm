package com.fatfreecrm.service.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.fatfreecrm.service.audit.VersionRecorder;
import com.fatfreecrm.service.history.RailsRowAttributes;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountWebsiteJob implements Job {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountWebsiteJob.class);

    @Autowired
    private JobsOwner jobsOwner;
    @Autowired
    private JobsHttpClient httpClient;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AddressRepository addressRepository;
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
            LOGGER.warn("jobs owner is rails; skipping AccountWebsiteJob");
            return;
        }
        long id = context.getMergedJobDataMap().getLong("accountId");
        try {
            perform(id);
        } catch (Exception exception) {
            throw new IllegalStateException("Account website enrichment failed for account " + id, exception);
        }
    }

    @Transactional
    public void perform(long accountId) throws IOException, InterruptedException {
        if (!jobsOwner.isSpring()) {
            return;
        }
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null || blank(account.getWebsite())) {
            return;
        }
        URI website = websiteUri(account.getWebsite());
        JobsHttpClient.Response response = httpClient.get(website, "text/html,application/xhtml+xml", "Fat Free CRM");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return;
        }
        Document document = Jsoup.parse(new String(response.body(), StandardCharsets.UTF_8), website.toString());
        for (Element script : document.select("script[type=application/ld+json]")) {
            processJsonLd(account, script.data());
        }
    }

    private void processJsonLd(Account account, String content) {
        JsonNode data;
        try {
            data = objectMapper.readTree(content);
        } catch (IOException exception) {
            return;
        }
        if (data == null) {
            return;
        }
        List<JsonNode> objects = new ArrayList<>();
        if (data.isArray()) {
            data.forEach(objects::add);
        } else {
            objects.add(data);
        }
        JsonNode graph = data.get("@graph");
        if (graph != null && graph.isArray()) {
            graph.forEach(objects::add);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode object : objects) {
            if (object != null && object.isObject() && seen.add(object.toString())) {
                if (hasType(object.get("@type"), "Organization")) {
                    updateFromOrganization(account, object);
                }
            }
        }
        for (JsonNode object : objects) {
            if (!hasType(object.get("@type"), "WebPage")) {
                continue;
            }
            JsonNode about = object.get("about");
            if (about == null) {
                continue;
            }
            List<JsonNode> aboutNodes = new ArrayList<>();
            if (about.isArray()) {
                about.forEach(aboutNodes::add);
            } else {
                aboutNodes.add(about);
            }
            for (JsonNode aboutNode : aboutNodes) {
                if (hasType(aboutNode.get("@type"), "Organization")) {
                    updateFromOrganization(account, aboutNode);
                } else if (aboutNode.has("@id")) {
                    String id = aboutNode.path("@id").asText();
                    objects.stream()
                        .filter(candidate -> id.equals(candidate.path("@id").asText())
                            && hasType(candidate.get("@type"), "Organization"))
                        .findFirst()
                        .ifPresent(candidate -> updateFromOrganization(account, candidate));
                }
            }
        }
        if (!blank(account.getWikidataId())) {
            enrichmentTrigger.afterAccountSaved(account.getId(), false, true);
        }
    }

    private void updateFromOrganization(Account account, JsonNode organization) {
        Map<String, Object> before = rowAttributes.read(account);
        boolean changed = false;
        changed |= fill(account.getPhone(), organization.path("telephone").asText(null), account::setPhone);
        changed |= fill(account.getEmail(), organization.path("email").asText(null), account::setEmail);
        changed |= fill(account.getFax(), organization.path("faxNumber").asText(null), account::setFax);
        JsonNode sameAs = organization.get("sameAs");
        if (sameAs != null) {
            List<JsonNode> urls = new ArrayList<>();
            if (sameAs.isArray()) {
                sameAs.forEach(urls::add);
            } else {
                urls.add(sameAs);
            }
            for (JsonNode urlNode : urls) {
                if (!urlNode.isTextual()) {
                    continue;
                }
                String url = urlNode.asText();
                if (account.getFacebook() == null && url.contains("facebook.com/")) {
                    account.setFacebook(url);
                    changed = true;
                } else if (account.getInstagram() == null && url.contains("instagram.com/")) {
                    account.setInstagram(url);
                    changed = true;
                } else if (account.getTwitter() == null && (url.contains("twitter.com/") || url.contains("x.com/"))) {
                    account.setTwitter(url);
                    changed = true;
                } else if (account.getLinkedin() == null && url.contains("linkedin.com/")) {
                    account.setLinkedin(url);
                    changed = true;
                } else if (account.getBluesky() == null && url.contains("bsky.app/")) {
                    account.setBluesky(url);
                    changed = true;
                } else if (account.getMastodon() == null && url.contains("mastodon")) {
                    account.setMastodon(url);
                    changed = true;
                }
            }
        }
        JsonNode geo = organization.path("geo");
        if ("GeoCoordinates".equals(geo.path("@type").asText())) {
            if (account.getLatitude() == null && geo.hasNonNull("latitude")) {
                account.setLatitude(geo.path("latitude").decimalValue());
                changed = true;
            }
            if (account.getLongitude() == null && geo.hasNonNull("longitude")) {
                account.setLongitude(geo.path("longitude").decimalValue());
                changed = true;
            }
        }
        if (changed) {
            account.setUpdatedAt(Instant.now());
            accountRepository.saveAndFlush(account);
            versionRecorder.recordUpdate(null, account, before, rowAttributes.read(account));
        }
        JsonNode address = organization.get("address");
        if (address != null && address.isObject()) {
            saveAddress(account, address);
        }
    }

    private void saveAddress(Account account, JsonNode data) {
        List<Address> existing = addressRepository.findByAddressableTypeAndAddressableId("Account",
            Math.toIntExact(account.getId()));
        if (existing.stream().anyMatch(address -> "Billing".equals(address.getAddressType()))) {
            return;
        }
        Address address = new Address();
        address.setAddressableType("Account");
        address.setAddressableId(Math.toIntExact(account.getId()));
        address.setAddressType("Billing");
        address.setStreet1(text(data, "streetAddress"));
        address.setCity(text(data, "addressLocality"));
        address.setState(text(data, "addressRegion"));
        address.setZipcode(text(data, "postalCode"));
        address.setCountry(text(data, "addressCountry"));
        if (address.getStreet1() != null || address.getCity() != null || address.getState() != null
            || address.getZipcode() != null || address.getCountry() != null) {
            address.setCreatedAt(Instant.now());
            address.setUpdatedAt(address.getCreatedAt());
            addressRepository.saveAndFlush(address);
            versionRecorder.recordCreate(null, address, rowAttributes.read(address),
                rowAttributes.defaults("addresses"));
        }
    }

    private static URI websiteUri(String value) {
        try {
            URI parsed = new URI(value);
            return parsed.getScheme() == null ? new URI("http://" + value) : parsed;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid account website URI", exception);
        }
    }

    private static boolean hasType(JsonNode type, String expected) {
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode value : type) {
                if (expected.equals(value.asText())) {
                    return true;
                }
            }
            return false;
        }
        return expected.equals(type.asText());
    }

    private static boolean fill(String current, String value, java.util.function.Consumer<String> setter) {
        if (blank(current) && !blank(value)) {
            setter.accept(value);
            return true;
        }
        return false;
    }

    private static String text(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.path(key).asText() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
