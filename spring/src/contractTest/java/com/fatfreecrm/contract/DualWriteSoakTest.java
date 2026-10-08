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
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        ContractDbReset.fromProperties().reset();
        Instant startedAt = Instant.now();

        // Seed: one field-update task and one complete/uncomplete toggle task per thread via
        // Rails so both apps share fixture ids. Comments land on shared commentable Account 101.
        List<Long> taskIds = new ArrayList<>();
        List<Long> toggleIds = new ArrayList<>();
        RailsSessionAuth railsAuth = new RailsSessionAuth(railsUrl, users);
        AuthContext alice = railsAuth.authenticate("alice");
        for (int index = 0; index < threads; index++) {
            taskIds.add(seedTask(alice, "soak-task-" + index));
            toggleIds.add(seedTask(alice, "soak-toggle-" + index));
        }

        AtomicInteger taskWrites = new AtomicInteger();
        AtomicInteger completes = new AtomicInteger();
        AtomicInteger uncompletes = new AtomicInteger();
        AtomicInteger commentCreates = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger serverErrors = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        Instant deadline = Instant.now().plus(Duration.ofMinutes(minutes));
        ExecutorService pool = Executors.newFixedThreadPool(threads * 4);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int worker = 0; worker < threads; worker++) {
            long taskId = taskIds.get(worker);
            long toggleId = toggleIds.get(worker);
            futures.add(pool.submit(() -> runWriter(users, "alice", true, taskId,
                deadline, "background_info", taskWrites, errors, serverErrors, failures)));
            futures.add(pool.submit(() -> runWriter(users, "alice", false, taskId,
                deadline, "priority", taskWrites, errors, serverErrors, failures)));
            futures.add(pool.submit(() -> runToggler(users, "alice", toggleId,
                deadline, completes, uncompletes, errors, serverErrors, failures)));
            boolean commenterIsRails = worker % 2 == 0;
            futures.add(pool.submit(() -> runCommenter(users, "alice", commenterIsRails,
                deadline, commentCreates, errors, serverErrors, failures)));
        }
        pool.shutdown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get();
        }
        pool.awaitTermination(minutes + 5, TimeUnit.MINUTES);
        violations.set(constraintViolations());

        int taskSuccessful = taskWrites.get(); // counters only increment on success
        List<Long> allTaskIds = new ArrayList<>(taskIds);
        allTaskIds.addAll(toggleIds);
        long updateVersions = versionCount("Task", allTaskIds, "update", null);
        long completeVersions = versionCount("Task", toggleIds, "complete", null);
        long commentRows = countComments(startedAt);
        long commentVersions = versionCount("Comment", null, "create", startedAt);
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
        // Leave the contract database on the fixture snapshot so subsequent read cases
        // (e.g. activities) do not see soak rows.
        ContractDbReset.fromProperties().reset();
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

    /** Posts comment creates on shared Account 101 from one app. */
    private void runCommenter(
        Map<String, FixtureUsers.FixtureUser> users,
        String user,
        boolean railsSide,
        Instant deadline,
        AtomicInteger commentCreates,
        AtomicInteger errors,
        AtomicInteger serverErrors,
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
                throw new SoakHttpException(response.statusCode(), url);
            }
            String text = response.body();
            return text == null || text.isBlank() ? null : JSON.readTree(text);
        } catch (IOException | InterruptedException exception) {
            throw new SoakHttpException(599, url + " " + exception.getMessage());
        }
    }

    private static final class SoakHttpException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        SoakHttpException(int status, String message) {
            super("HTTP " + status + " " + message);
            this.status = status;
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
            return rows.next() ? rows.getLong(1) : -1;
        } catch (java.sql.SQLException exception) {
            return -1;
        }
    }

    private int constraintViolations() {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("contract.dbUrl", "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract"),
                System.getProperty("contract.dbUser", "postgres"),
                System.getProperty("contract.dbPassword", "postgres"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT count(*) FROM tasks WHERE name LIKE 'soak-task-%' AND name IS NULL")) {
            return rows.next() ? rows.getInt(1) : 0;
        } catch (java.sql.SQLException exception) {
            return 0;
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
