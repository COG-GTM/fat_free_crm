package com.fatfreecrm.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * AB-272 dual-write soak ({@code ./gradlew dualWriteSoak -Psoak.minutes=N -Psoak.threads=T}):
 * concurrent Rails and Spring writes against the same rows — task field updates (distinct fields
 * per writer, unique values) plus comment creates on shared commentables. Asserts zero 5xx, zero
 * constraint violations, no lost updates (each task's final row holds the last value each app
 * wrote) and that every successful notable write produced exactly one version row per item.
 * The Rails {@code commentable.subscribed_users} read-modify-write race is measured, not asserted.
 */
class DualWriteSoakTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String railsUrl;
    private String springUrl;


    record SoakResult(int writes, int errors, int serverErrors, int violations) {
    }

    @Test
    void soak() throws Exception {
        railsUrl = trimSlash(System.getProperty("contract.railsUrl", "http://localhost:3000"));
        springUrl = trimSlash(System.getProperty("contract.springUrl", "http://localhost:8080"));
        int minutes = Integer.parseInt(System.getProperty("soak.minutes", "5"));
        int threads = Integer.parseInt(System.getProperty("soak.threads", "4"));
        // soak.commentSides: both (rails+spring alternated), spring, or rails.
        String commentSides = System.getProperty("soak.commentSides", "both");
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        ContractDbReset.fromProperties().reset();
        Instant startedAt = Instant.now();

        // Seed: one field-update task and one complete/uncomplete toggle task per thread via
        // Rails so both apps share fixture ids. Comments land on shared commentable Account 101.
        List<Long> taskIds = new ArrayList<>();
        List<Long> toggleIds = new ArrayList<>();
        List<Long> accountIds = new ArrayList<>();
        List<Long> contactIds = new ArrayList<>();
        List<Long> leadIds = new ArrayList<>();
        RailsSessionAuth railsAuth = new RailsSessionAuth(railsUrl, users);
        AuthContext alice = railsAuth.authenticate("alice");
        for (int index = 0; index < threads; index++) {
            taskIds.add(seedTask(alice, "soak-task-" + index));
            toggleIds.add(seedTask(alice, "soak-toggle-" + index));
            accountIds.add(seedEntity(alice, "accounts", "account",
                Map.of("name", "soak-account-" + index)));
            contactIds.add(seedEntity(alice, "contacts", "contact",
                Map.of("first_name", "soak", "last_name", "contact-" + index)));
            leadIds.add(seedEntity(alice, "leads", "lead",
                Map.of("first_name", "soak", "last_name", "lead-" + index)));
        }
        // Admin family (AB-272 Phase B): seed the settings rows via Rails so concurrent writers
        // only update (Setting[]= is find_by_name || new — racing creates would duplicate).
        AuthContext railsAdmin = railsAuth.authenticate("admin");
        ObjectNode seedSettings = JSON.createObjectNode();
        seedSettings.putObject("settings").put("host", "seed").put("base_url", "seed");
        sendJson(railsAdmin.client(), railsAdmin, "PUT", railsUrl + "/admin/settings", seedSettings);

        AtomicInteger taskWrites = new AtomicInteger();
        AtomicInteger completes = new AtomicInteger();
        AtomicInteger uncompletes = new AtomicInteger();
        AtomicInteger commentCreates = new AtomicInteger();
        AtomicInteger entityWrites = new AtomicInteger();
        AtomicInteger promotes = new AtomicInteger();
        AtomicInteger rejects = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger serverErrors = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        Instant deadline = Instant.now().plus(Duration.ofMinutes(minutes));
        ExecutorService pool = Executors.newFixedThreadPool(threads * 4 + 5);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < threads; worker++) {
            long taskId = taskIds.get(worker);
            long toggleId = toggleIds.get(worker);
            long accountId = accountIds.get(worker);
            long contactId = contactIds.get(worker);
            long leadId = leadIds.get(worker);
            futures.add(pool.submit(() -> runWriter(users, "alice", true, taskId,
                deadline, "background_info", taskWrites, errors, serverErrors, violations,
                failures)));
            futures.add(pool.submit(() -> runWriter(users, "alice", false, taskId,
                deadline, "priority", taskWrites, errors, serverErrors, violations,
                failures)));
            futures.add(pool.submit(() -> runToggler(users, "alice", toggleId,
                deadline, completes, uncompletes, errors, serverErrors, violations,
                failures)));
            boolean commenterIsRails = switch (commentSides) {
                case "rails" -> true;
                case "spring" -> false;
                default -> worker % 2 == 0;
            };
            futures.add(pool.submit(() -> runCommenter(users, "alice", commenterIsRails,
                deadline, commentCreates, errors, serverErrors, violations, failures)));
            futures.add(pool.submit(() -> runEntityWriter(users, "alice", "accounts",
                "Account", accountId, deadline, entityWrites, errors,
                serverErrors, violations, failures)));
            futures.add(pool.submit(() -> runEntityWriter(users, "alice", "contacts",
                "Contact", contactId, deadline, entityWrites, errors,
                serverErrors, violations, failures)));
            futures.add(pool.submit(() -> runPromoter(users, "alice", leadId,
                deadline, promotes, rejects, errors, serverErrors, violations, failures)));
        }
        AtomicInteger userWrites = new AtomicInteger();
        AtomicInteger suspends = new AtomicInteger();
        AtomicInteger reactivates = new AtomicInteger();
        AtomicInteger settingsWrites = new AtomicInteger();
        // Concurrent admin updates of different columns on carol (5) from both apps, a
        // suspend/reactivate toggler on bob (3) alternating apps, and settings writers.
        futures.add(pool.submit(() -> runAdminWriter(users, true, "/admin/users/" + SOAK_USER + ".json",
            "/api/v1/admin/users/" + SOAK_USER, "user", "title", "user:title", deadline, userWrites,
            errors, serverErrors, violations, failures)));
        futures.add(pool.submit(() -> runAdminWriter(users, false, "/admin/users/" + SOAK_USER + ".json",
            "/api/v1/admin/users/" + SOAK_USER, "user", "company", "user:company", deadline, userWrites,
            errors, serverErrors, violations, failures)));
        futures.add(pool.submit(() -> runSuspender(users, deadline, suspends, reactivates, errors,
            serverErrors, violations, failures)));
        futures.add(pool.submit(() -> runAdminWriter(users, true, "/admin/settings",
            "/api/v1/admin/settings", "settings", "host", "setting:host", deadline, settingsWrites,
            errors, serverErrors, violations, failures)));
        futures.add(pool.submit(() -> runAdminWriter(users, false, "/admin/settings",
            "/api/v1/admin/settings", "settings", "base_url", "setting:base_url", deadline,
            settingsWrites, errors, serverErrors, violations, failures)));
        pool.shutdown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get();
        }
        pool.awaitTermination(minutes + 5, TimeUnit.MINUTES);
        // End-state invariants: real integrity queries — a failed query fails the test.
        violations.addAndGet((int) endStateViolations());

        int taskSuccessful = taskWrites.get(); // counters only increment on success
        List<Long> allTaskIds = new ArrayList<>(taskIds);
        allTaskIds.addAll(toggleIds);
        long updateVersions = versionCount("Task", allTaskIds, "update", null);
        long completeVersions = versionCount("Task", toggleIds, "complete", null);
        long commentRows = countComments(startedAt);
        long accountVersions = versionCount("Account", accountIds, "update", null);
        long contactVersions = versionCount("Contact", contactIds, "update", null);
        long leadPromoteVersions = versionCount("Lead", leadIds, "update", null);
        long promoteContactVersions = versionCount("Contact", null, "create", startedAt);
        long commentVersions = versionCount("Comment", null, "create", startedAt);
        long userUpdateVersions = versionCount("User", List.of((long) SOAK_USER), "update", startedAt);
        long suspendVersions = versionCount("User", List.of((long) SUSPEND_USER), "update", startedAt);
        long settingVersions = queryLong("SELECT count(*) FROM versions WHERE item_type = 'Setting'");
        int subscribed = subscribedCount("accounts", 101);
        int expectedSubscriptions = commentCreates.get();
        StringBuilder report = new StringBuilder();
        report.append(String.format(
            "taskWrites=%d completes=%d uncompletes=%d comments=%d errors=%d 5xx=%d "
                + "constraintViolations=%d%n",
            taskWrites.get(), completes.get(), uncompletes.get(), commentCreates.get(),
            errors.get(), serverErrors.get(), violations.get()));
        report.append(String.format(
            "updateVersions=%d taskWrites+toggles=%d completeVersions=%d completes=%d%n",
            updateVersions, taskSuccessful + completes.get() + uncompletes.get(),
            completeVersions, completes.get()));
        report.append(String.format("commentRows=%d commentCreateVersions=%d%n",
            commentRows, commentVersions));
        report.append(String.format(
            "entityWrites=%d accountVersions=%d contactVersions=%d promotes=%d "
                + "rejects=%d leadVersions=%d promoteContactVersions=%d%n",
            entityWrites.get(), accountVersions, contactVersions, promotes.get(), rejects.get(),
            leadPromoteVersions, promoteContactVersions));
        report.append(String.format(
            "userWrites=%d userUpdateVersions=%d suspends=%d reactivates=%d suspendVersions=%d "
                + "settingsWrites=%d settingVersions=%d%n",
            userWrites.get(), userUpdateVersions, suspends.get(), reactivates.get(), suspendVersions,
            settingsWrites.get(), settingVersions));
        report.append(String.format(
            "OBSERVATION account-101 subscribed_users entries for user 2: %d of %d expected "
                + "comment-subscription appends persisted (lost=%d; Rails read-modify-write race,"
                + " Spring locks FOR UPDATE)%n",
            subscribed, expectedSubscriptions, Math.max(0, expectedSubscriptions - subscribed)));
        int lost = 0;
        for (long taskId : taskIds) {
            for (String field : List.of("background_info", "priority")) {
                String expected = LastWrites.LAST.get(taskId + ":" + field);
                String actual = column("tasks", taskId, field);
                if (expected != null && !expected.equals(actual == null ? "" : actual)) {
                    lost++;
                    report.append("LOST tasks/").append(taskId).append('/').append(field)
                        .append(" expected=").append(expected).append(" actual=").append(actual)
                        .append('\n');
                }
            }
        }
        for (String field : List.of("title", "company")) {
            String expected = LastWrites.LAST.get("user:" + field);
            String actual = column("users", SOAK_USER, field);
            if (expected != null && !expected.equals(actual)) {
                lost++;
                report.append("LOST users/").append(SOAK_USER).append('/').append(field)
                    .append(" expected=").append(expected).append(" actual=").append(actual).append('\n');
            }
        }
        for (String name : List.of("host", "base_url")) {
            String expected = LastWrites.LAST.get("setting:" + name);
            String actual = settingValue(name);
            if (expected != null && !("--- " + expected + "\n").equals(actual)) {
                lost++;
                report.append("LOST settings/").append(name).append(" expected=").append(expected)
                    .append(" actual=").append(actual).append('\n');
            }
        }
        System.out.print(report);
        org.junit.jupiter.api.Assertions.assertEquals(0, serverErrors.get(),
            "5xx responses: " + failures);
        org.junit.jupiter.api.Assertions.assertEquals(0, violations.get(),
            "constraint violations");
        org.junit.jupiter.api.Assertions.assertEquals(0, lost, "lost updates\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(
            taskWrites.get() + completes.get() + uncompletes.get(), updateVersions,
            "task update versions vs successful task writes\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(completes.get(), completeVersions,
            "complete observer versions vs successful completes\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(commentCreates.get(), commentVersions,
            "comment create versions vs successful comments\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(commentCreates.get(), commentRows,
            "comment rows vs successful comments\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(entityWrites.get(),
            accountVersions + contactVersions,
            "entity update versions vs successful entity writes\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(promotes.get() + rejects.get(),
            leadPromoteVersions,
            "lead update versions vs successful promote+reject writes\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(promotes.get(), promoteContactVersions,
            "contact create versions vs successful promotes\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(userWrites.get(), userUpdateVersions,
            "user update versions vs successful admin user updates\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(suspends.get() + reactivates.get(), suspendVersions,
            "user update versions vs successful suspends+reactivates\n" + report);
        org.junit.jupiter.api.Assertions.assertEquals(0, settingVersions,
            "Setting has no has_paper_trail\n" + report);
        // Leave the contract database on the fixture snapshot so subsequent read cases
        // (e.g. activities) do not see soak rows.
        ContractDbReset.fromProperties().reset();
    }

    private long seedEntity(AuthContext alice, String family, String key,
        Map<String, String> fields) throws SoakHttpException {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode entity = body.putObject(key);
        fields.forEach(entity::put);
        JsonNode created = sendJson(alice.client(), alice, "POST",
            railsUrl + "/" + family + ".json", body);
        return created.path("id").asLong();
    }

    private long seedTask(AuthContext alice, String name) throws SoakHttpException {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode task = body.putObject("task");
        task.put("user_id", 2);
        task.put("name", name);
        task.put("bucket", "due_today");
        JsonNode created = sendJson(alice.client(), alice, "POST", railsUrl + "/tasks.json", body);
        return created.path("id").asLong();
    }

    private void runWriter(
        Map<String, FixtureUsers.FixtureUser> users,
        String user,
        boolean railsSide,
        long taskId,
        Instant deadline,
        String field,
        AtomicInteger writes,
        AtomicInteger errors,
        AtomicInteger serverErrors,
        AtomicInteger violations,
        ConcurrentLinkedQueue<String> failures
    ) {
        String base = railsSide ? railsUrl : springUrl;
        try {
            AuthContext auth = railsSide
                ? new RailsSessionAuth(railsUrl, users).authenticate(user)
                : new SpringJwtAuth(springUrl, users).authenticate(user);
            int sequence = 0;
            while (Instant.now().isBefore(deadline)) {
                String value = (railsSide ? "rails" : "spring") + "-" + sequence++;
                ObjectNode body = JSON.createObjectNode();
                body.putObject("task").put(field, value);
                try {
                    sendJson(auth.client(), auth, "PUT",
                        base + (railsSide ? "/tasks/" + taskId + ".json"
                            : "/api/v1/tasks/" + taskId), body);
                    writes.incrementAndGet();
                    LastWrites.LAST.put(taskId + ":" + field, value);
                } catch (SoakHttpException exception) {
                    if (isConstraintViolation(exception)) {
                        violations.incrementAndGet();
                    }
                    if (exception.status >= 500) {
                        serverErrors.incrementAndGet();
                        failures.add((railsSide ? "rails" : "spring") + " " + exception.getMessage());
                    } else {
                        errors.incrementAndGet();
                    }
                }
            }
        } catch (IOException | InterruptedException exception) {
            failures.add("auth failed: " + exception.getMessage());
            serverErrors.incrementAndGet();
        }
    }

    /** Alternates complete/uncomplete on one task, switching apps each iteration. */
    private void runToggler(
        Map<String, FixtureUsers.FixtureUser> users,
        String user,
        long taskId,
        Instant deadline,
        AtomicInteger completes,
        AtomicInteger uncompletes,
        AtomicInteger errors,
        AtomicInteger serverErrors,
        AtomicInteger violations,
        ConcurrentLinkedQueue<String> failures
    ) {
        try {
            AuthContext railsContext = new RailsSessionAuth(railsUrl, users).authenticate(user);
            AuthContext springContext = new SpringJwtAuth(springUrl, users).authenticate(user);
            int sequence = 0;
            while (Instant.now().isBefore(deadline)) {
                // Strict complete/uncomplete alternation (each op flips state, so Rails PaperTrail
                // writes a version every time); apps alternate in pairs of two.
                boolean complete = sequence % 2 == 0;
                boolean railsSide = (sequence / 2) % 2 == 0;
                sequence++;
                String path = "/tasks/" + taskId + (complete ? "/complete" : "/uncomplete");
                try {
                    if (railsSide) {
                        sendJson(railsContext.client(), railsContext, "PUT",
                            railsUrl + path + ".json", JSON.createObjectNode());
                    } else {
                        sendJson(springContext.client(), springContext, "PUT",
                            springUrl + "/api/v1" + path, JSON.createObjectNode());
                    }
                    (complete ? completes : uncompletes).incrementAndGet();
                } catch (SoakHttpException exception) {
                    if (isConstraintViolation(exception)) {
                        violations.incrementAndGet();
                    }
                    if (exception.status >= 500) {
                        serverErrors.incrementAndGet();
                        failures.add("toggle " + exception.getMessage());
                    } else {
                        errors.incrementAndGet();
                    }
                }
            }
        } catch (IOException | InterruptedException exception) {
            failures.add("toggle auth failed: " + exception.getMessage());
            serverErrors.incrementAndGet();
        }
    }

    /**
     * Alternates Rails/Spring writes (pairs of two) of {@code background_info} on one CRM entity
     * per worker — each successful write must produce exactly one PaperTrail update version.
     */
    private void runEntityWriter(
        Map<String, FixtureUsers.FixtureUser> users,
        String user,
        String family,
        String railsName,
        long entityId,
        Instant deadline,
        AtomicInteger writes,
        AtomicInteger errors,
        AtomicInteger serverErrors,
        AtomicInteger violations,
        ConcurrentLinkedQueue<String> failures
    ) {
        try {
            AuthContext railsContext = new RailsSessionAuth(railsUrl, users).authenticate(user);
            AuthContext springContext = new SpringJwtAuth(springUrl, users).authenticate(user);
            String key = railsName.toLowerCase(java.util.Locale.ROOT);
            int sequence = 0;
            while (Instant.now().isBefore(deadline)) {
                boolean railsSide = (sequence / 2) % 2 == 0;
                String value = (railsSide ? "rails" : "spring") + "-" + sequence++;
                ObjectNode body = JSON.createObjectNode();
                body.putObject(key).put("background_info", value);
                try {
                    if (railsSide) {
                        sendJson(railsContext.client(), railsContext, "PUT",
                            railsUrl + "/" + family + "/" + entityId + ".json", body);
                    } else {
                        sendJson(springContext.client(), springContext, "PUT",
                            springUrl + "/api/v1/" + family + "/" + entityId, body);
                    }
                    writes.incrementAndGet();
                    LastWrites.LAST.put(entityId + ":" + key, value);
                } catch (SoakHttpException exception) {
                    if (isConstraintViolation(exception)) {
                        violations.incrementAndGet();
                    }
                    if (exception.status >= 500) {
                        serverErrors.incrementAndGet();
                        failures.add("entity " + exception.getMessage());
                    } else {
                        errors.incrementAndGet();
                    }
                }
            }
        } catch (IOException | InterruptedException exception) {
            failures.add("entity auth failed: " + exception.getMessage());
            serverErrors.incrementAndGet();
        }
    }

    /**
     * Alternates Rails/Spring promote calls on one seeded lead (each promote creates a fresh
     * account/opportunity/contact and flips status to converted — Rails does not guard repeat
     * promotes). Each success must produce one Lead update version and one Contact create version.
     */
    private void runPromoter(
        Map<String, FixtureUsers.FixtureUser> users,
        String user,
        long leadId,
        Instant deadline,
        AtomicInteger promotes,
        AtomicInteger rejects,
        AtomicInteger errors,
        AtomicInteger serverErrors,
        AtomicInteger violations,
        ConcurrentLinkedQueue<String> failures
    ) {
        try {
            AuthContext railsContext = new RailsSessionAuth(railsUrl, users).authenticate(user);
            AuthContext springContext = new SpringJwtAuth(springUrl, users).authenticate(user);
            // Reject/promote pairs keep flipping status so every write is notable and produces
            // exactly one Lead update version on either app; promotes add a Contact create.
            int sequence = 0;
            while (Instant.now().isBefore(deadline)) {
                boolean railsSide = (sequence / 2) % 2 == 0;
                boolean promote = sequence % 2 == 0;
                ObjectNode body = JSON.createObjectNode();
                String railsPath;
                String springPath;
                if (promote) {
                    body.putObject("account").put("name",
                        "soak-promo-acct-" + leadId + "-" + sequence);
                    body.putObject("opportunity").put("name",
                        "soak-promo-opp-" + leadId + "-" + sequence);
                    body.put("access", "Private");
                    railsPath = "/leads/" + leadId + "/promote.json";
                    springPath = "/api/v1/leads/" + leadId + "/promote";
                } else {
                    railsPath = "/leads/" + leadId + "/reject.json";
                    springPath = "/api/v1/leads/" + leadId + "/reject";
                }
                sequence++;
                try {
                    if (railsSide) {
                        sendJson(railsContext.client(), railsContext, "PUT",
                            railsUrl + railsPath, body);
                    } else {
                        sendJson(springContext.client(), springContext, "PUT",
                            springUrl + springPath, body);
                    }
                    (promote ? promotes : rejects).incrementAndGet();
                } catch (SoakHttpException exception) {
                    if (isConstraintViolation(exception)) {
                        violations.incrementAndGet();
                    }
                    if (exception.status >= 500) {
                        serverErrors.incrementAndGet();
                        failures.add("promote/reject " + exception.getMessage());
                    } else {
                        errors.incrementAndGet();
                    }
                }
            }
        } catch (IOException | InterruptedException exception) {
            failures.add("promote auth failed: " + exception.getMessage());
            serverErrors.incrementAndGet();
        }
    }

    /** Posts comment creates on shared Account 101 from one app. */
    private void runCommenter(
        Map<String, FixtureUsers.FixtureUser> users,
        String user,
        boolean railsSide,
        Instant deadline,
        AtomicInteger commentCreates,
        AtomicInteger errors,
        AtomicInteger serverErrors,
        AtomicInteger violations,
        ConcurrentLinkedQueue<String> failures
    ) {
        try {
            AuthContext auth = railsSide
                ? new RailsSessionAuth(railsUrl, users).authenticate(user)
                : new SpringJwtAuth(springUrl, users).authenticate(user);
            String base = railsSide ? railsUrl : springUrl;
            String path = railsSide ? "/comments.json" : "/api/v1/comments";
            int sequence = 0;
            while (Instant.now().isBefore(deadline)) {
                ObjectNode body = JSON.createObjectNode();
                ObjectNode comment = body.putObject("comment");
                comment.put("commentable_type", "Account");
                comment.put("commentable_id", 101);
                comment.put("comment",
                    (railsSide ? "rails" : "spring") + "-soak-" + sequence++);
                try {
                    sendJson(auth.client(), auth, "POST", base + path, body);
                    commentCreates.incrementAndGet();
                } catch (SoakHttpException exception) {
                    if (isConstraintViolation(exception)) {
                        violations.incrementAndGet();
                    }
                    if (exception.status >= 500) {
                        serverErrors.incrementAndGet();
                        failures.add("comment " + exception.getMessage());
                    } else {
                        errors.incrementAndGet();
                    }
                }
            }
        } catch (IOException | InterruptedException exception) {
            failures.add("comment auth failed: " + exception.getMessage());
            serverErrors.incrementAndGet();
        }
    }

    private static final int SOAK_USER = 5;
    private static final int SUSPEND_USER = 3;

    /**
     * Admin PUTs of {@code {root: {field: value}}} from one app as the fixture admin. Each value
     * is unique so every success changes the row (users: one PaperTrail update; settings: none).
     */
    private void runAdminWriter(
        Map<String, FixtureUsers.FixtureUser> users,
        boolean railsSide,
        String railsPath,
        String springPath,
        String root,
        String field,
        String lastKey,
        Instant deadline,
        AtomicInteger writes,
        AtomicInteger errors,
        AtomicInteger serverErrors,
        AtomicInteger violations,
        ConcurrentLinkedQueue<String> failures
    ) {
        String side = railsSide ? "rails" : "spring";
        try {
            AuthContext auth = railsSide
                ? new RailsSessionAuth(railsUrl, users).authenticate("admin")
                : new SpringJwtAuth(springUrl, users).authenticate("admin");
            String url = railsSide ? railsUrl + railsPath : springUrl + springPath;
            int sequence = 0;
            while (Instant.now().isBefore(deadline)) {
                String value = side + "-" + field + "-" + sequence++;
                ObjectNode body = JSON.createObjectNode();
                body.putObject(root).put(field, value);
                try {
                    sendJson(auth.client(), auth, "PUT", url, body);
                    writes.incrementAndGet();
                    LastWrites.LAST.put(lastKey, value);
                } catch (SoakHttpException exception) {
                    countFailure(exception, side + " " + root, errors, serverErrors, violations, failures);
                }
            }
        } catch (IOException | InterruptedException exception) {
            failures.add(root + " auth failed: " + exception.getMessage());
            serverErrors.incrementAndGet();
        }
    }

    /** Strict suspend/reactivate alternation on bob; apps alternate in pairs of two. */
    private void runSuspender(
        Map<String, FixtureUsers.FixtureUser> users,
        Instant deadline,
        AtomicInteger suspends,
        AtomicInteger reactivates,
        AtomicInteger errors,
        AtomicInteger serverErrors,
        AtomicInteger violations,
        ConcurrentLinkedQueue<String> failures
    ) {
        try {
            AuthContext railsContext = new RailsSessionAuth(railsUrl, users).authenticate("admin");
            AuthContext springContext = new SpringJwtAuth(springUrl, users).authenticate("admin");
            int sequence = 0;
            while (Instant.now().isBefore(deadline)) {
                boolean suspend = sequence % 2 == 0;
                boolean railsSide = (sequence / 2) % 2 == 0;
                sequence++;
                String path = "/admin/users/" + SUSPEND_USER + (suspend ? "/suspend" : "/reactivate");
                try {
                    if (railsSide) {
                        sendJson(railsContext.client(), railsContext, "PUT", railsUrl + path + ".json",
                            JSON.createObjectNode());
                    } else {
                        sendJson(springContext.client(), springContext, "PUT", springUrl + "/api/v1" + path,
                            JSON.createObjectNode());
                    }
                    (suspend ? suspends : reactivates).incrementAndGet();
                } catch (SoakHttpException exception) {
                    countFailure(exception, "suspend", errors, serverErrors, violations, failures);
                }
            }
        } catch (IOException | InterruptedException exception) {
            failures.add("suspend auth failed: " + exception.getMessage());
            serverErrors.incrementAndGet();
        }
    }

    private static void countFailure(SoakHttpException exception, String label, AtomicInteger errors,
        AtomicInteger serverErrors, AtomicInteger violations, ConcurrentLinkedQueue<String> failures) {
        if (isConstraintViolation(exception)) {
            violations.incrementAndGet();
        }
        if (exception.status >= 500) {
            serverErrors.incrementAndGet();
            failures.add(label + " " + exception.getMessage());
        } else {
            errors.incrementAndGet();
        }
    }

    private String settingValue(String name) {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("contract.dbUrl", "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract"),
                System.getProperty("contract.dbUser", "postgres"),
                System.getProperty("contract.dbPassword", "postgres"));
             java.sql.PreparedStatement statement = connection.prepareStatement(
                 "SELECT value FROM settings WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (java.sql.SQLException exception) {
            return null;
        }
    }

    private JsonNode sendJson(HttpClient client, AuthContext auth, String method, String url,
        ObjectNode body) throws SoakHttpException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json");
        if (auth.authorization() != null) {
            request.header("Authorization", auth.authorization());
        }
        if (auth.csrfToken() != null) {
            request.header("X-CSRF-Token", auth.csrfToken());
        }
        try {
            HttpResponse<String> response = client.send(
                request.method(method, HttpRequest.BodyPublishers.ofString(
                    body.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new SoakHttpException(response.statusCode(),
                    url + " " + response.body(), response.body());
            }
            String text = response.body();
            return text == null || text.isBlank() ? null : JSON.readTree(text);
        } catch (IOException | InterruptedException exception) {
            throw new SoakHttpException(599, url + " " + exception.getMessage(), "");
        }
    }

    private static final class SoakHttpException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final String body;
        SoakHttpException(int status, String message, String body) {
            super("HTTP " + status + " " + message);
            this.status = status;
            this.body = body;
        }
    }

    private static final class LastWrites {
        static final Map<String, String> LAST = new java.util.concurrent.ConcurrentHashMap<>();
    }

    private String column(String table, long id, String column) {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("contract.dbUrl", "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract"),
                System.getProperty("contract.dbUser", "postgres"),
                System.getProperty("contract.dbPassword", "postgres"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT " + column + " FROM " + table + " WHERE id = " + id)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (java.sql.SQLException exception) {
            return null;
        }
    }

    private long versionCount(String itemType, List<Long> itemIds, String event,
        Instant createdAfter) {
        StringBuilder sql = new StringBuilder(
            "SELECT count(*) FROM versions WHERE item_type = '" + itemType
                + "' AND event = '" + event + "'");
        if (itemIds != null) {
            sql.append(" AND item_id IN ")
                .append(itemIds.toString().replace("[", "(").replace("]", ")"));
        }
        if (createdAfter != null) {
            sql.append(" AND created_at >= '").append(createdAfter.toString()).append("'");
        }
        return queryLong(sql.toString());
    }

    private long countComments(Instant createdAfter) {
        return queryLong(
            "SELECT count(*) FROM comments WHERE created_at >= '" + createdAfter + "'");
    }

    /** Count of user-2 entries inside the {@code subscribed_users} YAML of {@code table.id}. */
    private int subscribedCount(String table, long id) {
        String yaml = column(table, id, "subscribed_users");
        if (yaml == null) {
            return 0;
        }
        int count = 0;
        for (String line : yaml.split("\n")) {
            if (line.trim().equals("- 2")) {
                count++;
            }
        }
        return count;
    }

    private long queryLong(String sql) {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("contract.dbUrl", "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract"),
                System.getProperty("contract.dbUser", "postgres"),
                System.getProperty("contract.dbPassword", "postgres"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("soak invariant query failed: " + sql, exception);
        }
    }

    /**
     * A failed write counts as a constraint violation when its error carries a 23xxx SQLState or
     * a constraint/duplicate-key marker (Rails renders DB errors as 500 text, Spring as 500 JSON).
     */
    private static boolean isConstraintViolation(SoakHttpException exception) {
        String body = exception.body == null ? "" : exception.body.toLowerCase();
        return body.matches("(?s).*\\b23[0-9a-z]{4}\\b.*")
            || body.contains("constraint") || body.contains("duplicate key")
            || body.contains("violat");
    }

    /**
     * End-state integrity invariants, each == 0 expected: orphan comments (commentable row gone),
     * versions pointing at a missing item (create/update events), and duplicate live task
     * (user_id, name) pairs that index_tasks_on_user_id_and_name_and_deleted_at forbids.
     */
    private long endStateViolations() {
        long orphans = 0;
        orphans += queryLong(
            "SELECT count(*) FROM comments c WHERE c.commentable_type = 'Account'"
                + " AND NOT EXISTS (SELECT 1 FROM accounts a WHERE a.id = c.commentable_id)");
        orphans += queryLong(
            "SELECT count(*) FROM comments c WHERE c.commentable_type = 'Task'"
                + " AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = c.commentable_id)");
        orphans += queryLong(
            "SELECT count(*) FROM versions v WHERE v.event IN ('create', 'update')"
                + " AND v.item_type = 'Task'"
                + " AND NOT EXISTS (SELECT 1 FROM tasks t WHERE t.id = v.item_id)");
        orphans += queryLong(
            "SELECT count(*) FROM versions v WHERE v.event IN ('create', 'update')"
                + " AND v.item_type = 'Comment'"
                + " AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.id = v.item_id)");
        orphans += queryLong(
            "SELECT count(*) FROM (SELECT user_id, name FROM tasks WHERE deleted_at IS NULL"
                + " GROUP BY user_id, name HAVING count(*) > 1) dup");
        orphans += queryLong(
            "SELECT count(*) FROM versions v WHERE v.event IN ('create', 'update')"
                + " AND v.item_type = 'User'"
                + " AND NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v.item_id)");
        orphans += queryLong(
            "SELECT count(*) FROM (SELECT name FROM settings GROUP BY name HAVING count(*) > 1) dup");
        return orphans;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
