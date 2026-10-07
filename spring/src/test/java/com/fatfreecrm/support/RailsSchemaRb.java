package com.fatfreecrm.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal reader for the Rails {@code db/schema.rb} so Java tests can pin the Flyway baseline
 * to the schema Rails itself declares, instead of restating the generated SQL.
 */
public final class RailsSchemaRb {

    public static final Path DEFAULT_LOCATION = Path.of("../db/schema.rb");

    private static final Pattern CREATE_TABLE = Pattern.compile("^\\s*create_table \"(\\w+)\"(.*)do \\|t\\|\\s*$");
    private static final Pattern COLUMN = Pattern.compile("^\\s*t\\.(\\w+) \"(\\w+)\"(.*)$");
    private static final Pattern FOREIGN_KEY =
        Pattern.compile("^\\s*add_foreign_key \"(\\w+)\", \"(\\w+)\"(?:, column: \"(\\w+)\")?.*$");
    private static final Pattern VERSION =
        Pattern.compile("^\\s*ActiveRecord::Schema\\[[^\\]]+\\]\\.define\\(version: ([0-9_]+)\\)");

    public record Column(String name, boolean nullable) {
    }

    public record ForeignKey(String fromTable, String toTable, String column) {
    }

    private final String version;
    private final Map<String, List<Column>> tables;
    private final List<ForeignKey> foreignKeys;

    private RailsSchemaRb(String version, Map<String, List<Column>> tables, List<ForeignKey> foreignKeys) {
        this.version = version;
        this.tables = tables;
        this.foreignKeys = foreignKeys;
    }

    public static RailsSchemaRb load() throws IOException {
        return parse(Files.readAllLines(DEFAULT_LOCATION));
    }

    static RailsSchemaRb parse(List<String> lines) {
        String version = null;
        Map<String, List<Column>> tables = new LinkedHashMap<>();
        List<ForeignKey> foreignKeys = new ArrayList<>();
        List<Column> current = null;
        for (String line : lines) {
            Matcher versionMatch = VERSION.matcher(line);
            if (versionMatch.find()) {
                version = versionMatch.group(1).replace("_", "");
                continue;
            }
            Matcher tableMatch = CREATE_TABLE.matcher(line);
            if (tableMatch.matches()) {
                current = new ArrayList<>();
                if (!tableMatch.group(2).contains("id: false")) {
                    current.add(new Column("id", false));
                }
                tables.put(tableMatch.group(1), current);
                continue;
            }
            if (line.trim().equals("end")) {
                current = null;
                continue;
            }
            Matcher columnMatch = COLUMN.matcher(line);
            if (current != null && columnMatch.matches() && !"index".equals(columnMatch.group(1))) {
                current.add(new Column(columnMatch.group(2), !columnMatch.group(3).contains("null: false")));
                continue;
            }
            Matcher foreignKeyMatch = FOREIGN_KEY.matcher(line);
            if (foreignKeyMatch.matches()) {
                String column = foreignKeyMatch.group(3) != null
                    ? foreignKeyMatch.group(3)
                    : singular(foreignKeyMatch.group(2)) + "_id";
                foreignKeys.add(new ForeignKey(foreignKeyMatch.group(1), foreignKeyMatch.group(2), column));
            }
        }
        if (version == null) {
            throw new IllegalStateException("db/schema.rb does not declare an ActiveRecord::Schema version");
        }
        return new RailsSchemaRb(version, tables, foreignKeys);
    }

    private static String singular(String plural) {
        return plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
    }

    public String version() {
        return version;
    }

    public Set<String> tableNames() {
        return new LinkedHashSet<>(tables.keySet());
    }

    public Map<String, List<Column>> tables() {
        return tables;
    }

    public List<ForeignKey> foreignKeys() {
        return foreignKeys;
    }
}
