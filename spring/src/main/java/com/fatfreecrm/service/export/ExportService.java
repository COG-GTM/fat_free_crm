package com.fatfreecrm.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fatfreecrm.customfields.CustomFieldDefinition;
import com.fatfreecrm.customfields.CustomFieldRegistry;
import com.fatfreecrm.domain.support.BaseEntity;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.domain.support.RailsYaml;
import com.fatfreecrm.repository.ExportLookupRepository;
import com.fatfreecrm.repository.RailsRow;
import com.fatfreecrm.repository.RailsRowRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.json.RailsResource;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.query.CrmQueryService;
import com.fatfreecrm.service.query.ListQuery;
import com.fatfreecrm.service.query.ListResult;
import com.fatfreecrm.service.read.ActivitiesReadService;
import com.fatfreecrm.service.read.TaskReadService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

/**
 * Rails {@code format.csv} / {@code format.xls} list exports. Rows come from the same list paths as the JSON reads
 * but unpaginated (app/controllers/entities_controller.rb {@code get_list_of_records}: {@code unless wants.xls? ||
 * wants.csv?} skips {@code paginate}).
 */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Spring-managed collaborators are intentionally retained by this service."
)
public class ExportService {

    public enum Format { CSV, XLS }

    public record ExportDocument(String contentType, String contentDisposition, byte[] body) {
        public ExportDocument {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    static final String CSV_CONTENT_TYPE = "text/csv";
    static final String XLS_CONTENT_TYPE = "application/vnd.msexcel; charset=utf-8";
    private static final String EXPORT_PAGE_SIZE = "200";
    private static final Pattern SECRET_COLUMN = Pattern.compile("(?i).*(password|token|salt).*");
    private static final List<String> ADDRESS_COLUMNS =
        List.of("street1", "street2", "city", "state", "zipcode", "country", "full_address");
    private static final Map<String, String> BUCKET_LABELS = Map.ofEntries(
        Map.entry("overdue", "Overdue"), Map.entry("due_asap", "As Soon As Possible"),
        Map.entry("due_today", "Today"), Map.entry("due_tomorrow", "Tomorrow"),
        Map.entry("due_this_week", "This Week"), Map.entry("due_next_week", "Next Week"),
        Map.entry("due_later", "Sometime Later"), Map.entry("completed_today", "Today"),
        Map.entry("completed_yesterday", "Yesterday"), Map.entry("completed_last_week", "Last week"),
        Map.entry("completed_this_month", "This month"), Map.entry("completed_last_month", "Last month"));

    private final CrmQueryService crmQueryService;
    private final RailsResources resources;
    private final RailsRowRepository rowRepository;
    private final ExportLookupRepository lookups;
    private final CustomFieldRegistry customFields;
    private final TaskReadService taskReadService;
    private final ActivitiesReadService activitiesReadService;
    private final Clock clock;

    public ExportService(
        CrmQueryService crmQueryService,
        RailsResources resources,
        RailsRowRepository rowRepository,
        ExportLookupRepository lookups,
        CustomFieldRegistry customFields,
        TaskReadService taskReadService,
        ActivitiesReadService activitiesReadService,
        Clock clock
    ) {
        this.crmQueryService = crmQueryService;
        this.resources = resources;
        this.rowRepository = rowRepository;
        this.lookups = lookups;
        this.customFields = customFields;
        this.taskReadService = taskReadService;
        this.activitiesReadService = activitiesReadService;
        this.clock = clock;
    }

    /** Accounts, campaigns, contacts, leads and opportunities: the AB-269 list, every page, same order. */
    @Transactional(readOnly = true)
    public ExportDocument list(AuthenticatedUser user, RailsResource resource, ListQuery query, Format format) {
        List<RailsRow> rows = rows(resource, allIds(user, resource, query));
        if (format == Format.CSV) {
            return csv(resource.controllerName(), resource, rows);
        }
        List<CustomFieldDefinition> fields = customFields.definitionsFor(modelType(resource));
        List<String> header = new ArrayList<>(staticHeader(resource));
        fields.forEach(field -> header.add(field.label()));
        List<List<Object>> cells = xlsRows(resource, rows);
        Set<String> checkBoxes = checkBoxColumns(resource);
        for (int index = 0; index < rows.size(); index++) {
            RailsRow row = rows.get(index);
            for (CustomFieldDefinition field : fields) {
                String column = field.name().startsWith("cf_") ? field.name() : "cf_" + field.name();
                cells.get(index).add(value(resource, checkBoxes, column, row.columns().get(column)));
            }
        }
        return xls(worksheet(resource), header, cells, !rows.isEmpty());
    }

    /** {@code Task.find_all_grouped(current_user, view)} flattened in bucket order (tasks_controller.rb:14-22). */
    @Transactional(readOnly = true)
    public ExportDocument tasks(AuthenticatedUser user, String view, ZoneId zone, Format format) {
        List<Long> ids = new ArrayList<>();
        taskReadService.buckets(user, view, zone).values()
            .forEach(bucket -> bucket.forEach(task -> ids.add(task.get("id").asLong())));
        RailsResource resource = resources.task;
        List<RailsRow> rows = rows(resource, ids);
        if (format == Format.CSV) {
            return csv("tasks", resource, rows);
        }
        Map<Long, String> users = lookups.userNames(foreignKeys(rows, "user_id", "assigned_to"));
        Instant now = clock.instant();
        List<List<Object>> cells = new ArrayList<>();
        for (RailsRow row : rows) {
            Map<String, Object> columns = row.columns();
            cells.add(cells(row.id(), columns.get("name"),
                BUCKET_LABELS.getOrDefault(computedBucket(columns, now, zone),
                    "Translation missing: en-US." + computedBucket(columns, now, zone)),
                columns.get("created_at"), columns.get("updated_at"), columns.get("completed_at"),
                users.get(id(columns.get("user_id"))), users.get(id(columns.get("assigned_to"))),
                columns.get("category"), columns.get("background_info")));
        }
        return xls("Tasks", ExportHeaders.TASKS, cells, true);
    }

    /** {@code home#index}: {@code Version.latest(options).visible_to(current_user)} (home_controller.rb). */
    @Transactional(readOnly = true)
    public ExportDocument activities(AuthenticatedUser user, MultiValueMap<String, String> parameters, Format format) {
        List<ObjectNode> visible = activitiesReadService.list(user, parameters);
        List<Long> ids = visible.stream().map(version -> version.get("id").asLong()).toList();
        RailsResource resource = resources.version;
        List<RailsRow> rows = new ArrayList<>();
        Map<Long, RailsRow> byId = byId(rowRepository.findByIds(resource, ids));
        for (ObjectNode version : visible) {
            RailsRow raw = byId.get(version.get("id").asLong());
            if (raw == null) {
                continue;
            }
            Map<String, Object> columns = new LinkedHashMap<>(raw.columns());
            columns.put("object", text(version.get("object")));
            columns.put("object_changes", text(version.get("object_changes")));
            rows.add(new RailsRow(raw.id(), columns, raw.typeNames()));
        }
        if (format == Format.CSV) {
            return csv("home", resource, rows);
        }
        List<List<Object>> cells = new ArrayList<>();
        for (RailsRow row : rows) {
            Map<String, Object> columns = row.columns();
            cells.add(cells(row.id(), columns.get("item_type"), columns.get("item_id"), columns.get("event"),
                columns.get("whodunnit"), columns.get("object"), columns.get("created_at"),
                columns.get("object_changes"), columns.get("related_id"), columns.get("related_type"),
                columns.get("transaction_id")));
        }
        return xls("Dashboard", ExportHeaders.ACTIVITIES, cells, !rows.isEmpty());
    }

    private List<Long> allIds(AuthenticatedUser user, RailsResource resource, ListQuery query) {
        Set<Long> ids = new LinkedHashSet<>();
        int page = 1;
        while (true) {
            ListQuery pageQuery = new ListQuery(String.valueOf(page), EXPORT_PAGE_SIZE, query.query(), query.sortBy(),
                query.q(), query.preferredPerPage(), query.preferredSortBy(), query.filter());
            ListResult<?> result = crmQueryService.list(user, resource.entityClass(), pageQuery);
            result.items().forEach(item -> ids.add(((BaseEntity) item).getId()));
            if (result.items().isEmpty() || page >= result.totalPages()) {
                return new ArrayList<>(ids);
            }
            page++;
        }
    }

    private List<RailsRow> rows(RailsResource resource, List<Long> ids) {
        Map<Long, RailsRow> byId = byId(rowRepository.findByIds(resource, ids));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static Map<Long, RailsRow> byId(List<RailsRow> rows) {
        Map<Long, RailsRow> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        return byId;
    }

    /** lib/fat_free_crm/export_csv.rb + the {@code :csv} renderer in lib/fat_free_crm/renderers.rb. */
    private ExportDocument csv(String filename, RailsResource resource, List<RailsRow> rows) {
        List<String> columns = new ArrayList<>();
        if (!rows.isEmpty()) {
            rows.get(0).columns().keySet().stream()
                .filter(column -> exportable(resource, column))
                .forEach(columns::add);
        }
        List<String> header = new ArrayList<>(columns.stream().map(ExportService::humanize).toList());
        Map<Long, List<String>> tags = Map.of();
        if (resource.taggable()) {
            header.add("Tags");
            tags = lookups.tags(resource.railsModel(), rows.stream().map(RailsRow::id).toList());
        }
        Set<String> checkBoxes = checkBoxColumns(resource);
        List<List<Object>> values = new ArrayList<>();
        for (RailsRow row : rows) {
            List<Object> line = new ArrayList<>();
            columns.forEach(column -> line.add(value(resource, checkBoxes, column, row.columns().get(column))));
            if (resource.taggable()) {
                line.add(String.join(" ", tags.getOrDefault(row.id(), List.of())));
            }
            values.add(line);
        }
        return new ExportDocument(CSV_CONTENT_TYPE, "attachment; filename=" + filename + ".csv",
            RailsCsvWriter.write(header, values).getBytes(StandardCharsets.UTF_8));
    }

    private static ExportDocument xls(String worksheet, List<String> header, List<List<Object>> rows, boolean head) {
        return new ExportDocument(XLS_CONTENT_TYPE, null,
            SpreadsheetMlWriter.write(worksheet, header, rows, head).getBytes(StandardCharsets.UTF_8));
    }

    /** Rails ignores {@code custom_fields} and drops /password|token/; Spring also never exports salts. */
    private static boolean exportable(RailsResource resource, String column) {
        return !resource.excludedColumns().contains(column) && !"custom_fields".equals(column)
            && !SECRET_COLUMN.matcher(column).matches();
    }

    /** ActiveSupport {@code humanize}, which is what {@code human_attribute_name} falls back to. */
    static String humanize(String column) {
        String words = column.endsWith("_id") ? column.substring(0, column.length() - 3) : column;
        words = words.replaceFirst("^_+", "").replace('_', ' ').toLowerCase(Locale.ROOT);
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private Object value(RailsResource resource, Set<String> checkBoxes, String column, Object raw) {
        if (resource.yamlArrayColumns().contains(column) || checkBoxes.contains(column)) {
            Object parsed = raw instanceof String yaml && !yaml.isBlank() ? RailsYaml.read(yaml) : null;
            return parsed instanceof List<?> list ? list : List.of();
        }
        return raw;
    }

    private Set<String> checkBoxColumns(RailsResource resource) {
        Set<String> columns = new LinkedHashSet<>();
        RailsModelType.fromRailsName(resource.railsModel()).ifPresent(type -> customFields.definitionsFor(type)
            .stream()
            .filter(field -> "check_boxes".equals(field.as()))
            .forEach(field -> columns.add(field.name().startsWith("cf_") ? field.name() : "cf_" + field.name())));
        return columns;
    }

    private static RailsModelType modelType(RailsResource resource) {
        return RailsModelType.fromRailsName(resource.railsModel()).orElseThrow();
    }

    private List<String> staticHeader(RailsResource resource) {
        return switch (resource.table()) {
            case "accounts" -> ExportHeaders.ACCOUNTS;
            case "campaigns" -> ExportHeaders.CAMPAIGNS;
            case "contacts" -> ExportHeaders.CONTACTS;
            case "leads" -> ExportHeaders.LEADS;
            case "opportunities" -> ExportHeaders.OPPORTUNITIES;
            default -> throw new IllegalArgumentException("No XLS export for " + resource.table());
        };
    }

    private static String worksheet(RailsResource resource) {
        String table = resource.table();
        return Character.toUpperCase(table.charAt(0)) + table.substring(1);
    }

    /** The {@code data = [...]} arrays of app/views/{accounts,campaigns,contacts,leads,opportunities}/index.xls. */
    private List<List<Object>> xlsRows(RailsResource resource, List<RailsRow> rows) {
        List<Long> ids = rows.stream().map(RailsRow::id).toList();
        Map<Long, String> users = lookups.userNames(foreignKeys(rows, "user_id", "assigned_to"));
        Function<Object, String> user = key -> users.get(id(key));
        List<List<Object>> result = new ArrayList<>();
        switch (resource.table()) {
            case "accounts" -> {
                Map<Long, Map<String, Object>> addresses = lookups.addresses("Account", "Billing", ids);
                for (RailsRow row : rows) {
                    Map<String, Object> c = row.columns();
                    List<Object> cells = cells(row.id(), user.apply(c.get("user_id")),
                        user.apply(c.get("assigned_to")), c.get("name"), c.get("email"), c.get("phone"),
                        c.get("fax"), c.get("website"), c.get("background_info"), c.get("access"),
                        c.get("toll_free_phone"), c.get("rating"), c.get("category"), c.get("created_at"),
                        c.get("updated_at"));
                    addAddress(cells, addresses.get(row.id()));
                    result.add(cells);
                }
            }
            case "campaigns" -> {
                for (RailsRow row : rows) {
                    Map<String, Object> c = row.columns();
                    result.add(cells(row.id(), user.apply(c.get("user_id")), user.apply(c.get("assigned_to")),
                        c.get("name"), c.get("access"), c.get("status"), c.get("budget"), c.get("target_leads"),
                        c.get("target_conversion"), c.get("target_revenue"), c.get("leads_count"),
                        c.get("opportunities_count"), c.get("revenue"), c.get("starts_on"), c.get("ends_on"),
                        c.get("objectives"), c.get("background_info"), c.get("created_at"), c.get("updated_at")));
                }
            }
            case "contacts" -> {
                Map<Long, Map<String, Object>> addresses = lookups.addresses("Contact", "Business", ids);
                Map<Long, String> leads = lookups.leadNames(foreignKeys(rows, "lead_id"));
                for (RailsRow row : rows) {
                    Map<String, Object> c = row.columns();
                    List<Object> cells = cells(row.id(), leads.get(id(c.get("lead_id"))), c.get("title"),
                        fullName(c), c.get("first_name"), c.get("last_name"), c.get("email"), c.get("alt_email"),
                        c.get("phone"), c.get("mobile"), c.get("fax"), c.get("born_on"), c.get("background_info"),
                        c.get("blog"), c.get("linkedin"), c.get("facebook"), c.get("twitter"), c.get("created_at"),
                        c.get("updated_at"), user.apply(c.get("assigned_to")), c.get("access"),
                        c.get("department"), c.get("source"), c.get("do_not_call"));
                    addAddress(cells, addresses.get(row.id()));
                    result.add(cells);
                }
            }
            case "leads" -> {
                Map<Long, Map<String, Object>> addresses = lookups.addresses("Lead", "Business", ids);
                Map<Long, String> campaigns = lookups.campaignNames(foreignKeys(rows, "campaign_id"));
                for (RailsRow row : rows) {
                    Map<String, Object> c = row.columns();
                    List<Object> cells = cells(row.id(), user.apply(c.get("user_id")),
                        campaigns.get(id(c.get("campaign_id"))), c.get("title"), fullName(c), c.get("email"),
                        c.get("alt_email"), c.get("phone"), c.get("mobile"), c.get("company"),
                        c.get("background_info"), c.get("blog"), c.get("linkedin"), c.get("facebook"),
                        c.get("twitter"), c.get("created_at"), c.get("updated_at"), user.apply(c.get("assigned_to")),
                        c.get("access"), c.get("source"), c.get("status"), c.get("rating"), c.get("do_not_call"));
                    addAddress(cells, addresses.get(row.id()));
                    result.add(cells);
                }
            }
            case "opportunities" -> {
                Map<Long, String> campaigns = lookups.campaignNames(foreignKeys(rows, "campaign_id"));
                Map<Long, String> accounts = lookups.opportunityAccountNames(ids);
                for (RailsRow row : rows) {
                    Map<String, Object> c = row.columns();
                    result.add(cells(row.id(), user.apply(c.get("user_id")), campaigns.get(id(c.get("campaign_id"))),
                        user.apply(c.get("assigned_to")), accounts.get(row.id()), c.get("name"), c.get("access"),
                        c.get("source"), c.get("stage"), c.get("probability"), c.get("amount"), c.get("discount"),
                        weightedAmount(c), c.get("closes_on"), c.get("created_at"), c.get("updated_at")));
                }
            }
            default -> throw new IllegalArgumentException("No XLS export for " + resource.table());
        }
        return result;
    }

    private static void addAddress(List<Object> cells, Map<String, Object> address) {
        ADDRESS_COLUMNS.forEach(column -> cells.add(address == null ? null : address.get(column)));
    }

    /** {@code Contact#full_name} / {@code Lead#full_name} with no format: {@code "#{first_name} #{last_name}"}. */
    private static String fullName(Map<String, Object> columns) {
        return Objects.toString(columns.get("first_name"), "") + " " + Objects.toString(columns.get("last_name"), "");
    }

    /** {@code Opportunity#weighted_amount}: {@code (amount.to_f - discount.to_f) * probability.to_i / 100.0}. */
    static double weightedAmount(Map<String, Object> columns) {
        double amount = columns.get("amount") instanceof Number number ? number.doubleValue() : 0.0;
        double discount = columns.get("discount") instanceof Number number ? number.doubleValue() : 0.0;
        long probability = columns.get("probability") instanceof Number number ? number.longValue() : 0L;
        return (amount - discount) * probability / 100.0;
    }

    /** {@code Task#computed_bucket} (app/models/polymorphic/task.rb:158-174, 248-270). */
    static String computedBucket(Map<String, Object> columns, Instant now, ZoneId zone) {
        String bucket = (String) columns.get("bucket");
        if (!"specific_time".equals(bucket)) {
            return bucket;
        }
        Instant due = instant(columns.get("due_at"));
        if (due == null) {
            return "due_later";
        }
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant midnight = today.atStartOfDay(zone).toInstant();
        Instant tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant dayAfter = today.plusDays(2).atStartOfDay(zone).toInstant();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant weekStart = monday.atStartOfDay(zone).toInstant();
        Instant nextWeek = monday.plusWeeks(1).atStartOfDay(zone).toInstant();
        Instant weekAfter = monday.plusWeeks(2).atStartOfDay(zone).toInstant();
        if (due.isBefore(midnight)) {
            return "overdue";
        } else if (due.isBefore(tomorrow)) {
            return "due_today";
        } else if (due.isBefore(dayAfter)) {
            return "due_tomorrow";
        } else if (!due.isBefore(weekStart) && due.isBefore(nextWeek)) {
            return "due_this_week";
        } else if (!due.isBefore(nextWeek) && due.isBefore(weekAfter)) {
            return "due_next_week";
        }
        return "due_later";
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC);
        }
        return null;
    }

    private static Collection<Long> foreignKeys(List<RailsRow> rows, String... columns) {
        Set<Long> ids = new LinkedHashSet<>();
        for (RailsRow row : rows) {
            for (String column : columns) {
                Long id = id(row.columns().get(column));
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static Long id(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static List<Object> cells(Object... values) {
        List<Object> cells = new ArrayList<>(values.length + 8);
        java.util.Collections.addAll(cells, values);
        return cells;
    }
}
