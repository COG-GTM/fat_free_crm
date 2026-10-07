package com.fatfreecrm.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Round-trips Rails {@code serialize :subscribed_users, type: Array} columns.
 *
 * <p>Rails writes {@code YAML.dump} ({@code ---\n- 1\n- 2\n}) for a non-empty array and {@code NULL} when the array
 * equals the serializer default {@code []}. The writer below emits exactly that so a Spring-saved row is
 * indistinguishable from a Rails one; the reader accepts {@code NULL}, {@code --- []} and any YAML sequence of
 * integers (block or flow style, quoted or not) so legacy rows still load.</p>
 */
@Converter
public class RailsYamlIdListConverter implements AttributeConverter<List<Long>, String> {


    @Override
    public String convertToDatabaseColumn(List<Long> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        var yaml = new StringBuilder("---\n");
        for (Long id : attribute) {
            yaml.append("- ").append(id).append('\n');
        }
        return yaml.toString();
    }

    @Override
    public List<Long> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(dbData);
        if (loaded == null) {
            return new ArrayList<>();
        }
        if (!(loaded instanceof List<?> items)) {
            throw new IllegalArgumentException("subscribed_users is not a YAML sequence: " + dbData);
        }
        var ids = new ArrayList<Long>(items.size());
        for (Object item : items) {
            ids.add(toId(item, dbData));
        }
        return ids;
    }

    private static Long toId(Object item, String dbData) {
        if (item instanceof Number number) {
            return number.longValue();
        }
        if (item instanceof String text) {
            return Long.parseLong(text.trim());
        }
        throw new IllegalArgumentException("subscribed_users contains a non-integer entry: " + dbData);
    }
}
