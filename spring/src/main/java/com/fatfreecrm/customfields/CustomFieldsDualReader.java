package com.fatfreecrm.customfields;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dual-read shim: merges legacy cf_* column values and the custom_fields jsonb
 * document, mirroring the SQL trigger's precedence rules on the Java side.
 * A physically present cf_ column wins (even when NULL = field cleared);
 * otherwise the jsonb value is used, decoding a {"$yaml": ...} marker left by
 * the trigger for check_boxes YAML it could not decode.
 */
final class CustomFieldsDualReader {

    private CustomFieldsDualReader() {
    }

    /**
     * @param cfColumns        map of cf_ column name -> raw column value (only
     *                         columns that physically exist are present)
     * @param customFieldsJsonb the JSONB document
     * @param metadata         field definitions for the klass
     * @return normalized custom-field values, READ-mode validated
     */
    static Map<String, Object> read(Map<String, Object> cfColumns,
        Map<String, Object> customFieldsJsonb, List<CustomFieldDefinition> metadata) {
        CheckBoxesYamlCodec codec = new CheckBoxesYamlCodec();
        Map<String, Object> merged = new LinkedHashMap<>();
        for (CustomFieldDefinition field : metadata) {
            String name = field.name();
            if (cfColumns.containsKey(name)) {
                // column physically exists -> column wins, null included
                Object col = cfColumns.get(name);
                if (col == null) {
                    continue; // cleared field: key absent from output
                }
                if ("check_boxes".equals(field.as()) && col instanceof CharSequence s) {
                    merged.put(name, codec.decode(s.toString()));
                } else {
                    merged.put(name, col);
                }
            } else if (customFieldsJsonb.containsKey(name)) {
                Object v = customFieldsJsonb.get(name);
                if (v == null) {
                    continue;
                }
                if (v instanceof Map<?, ?> marker && marker.size() == 1 && marker.containsKey("$yaml")) {
                    merged.put(name, codec.decode(String.valueOf(marker.get("$yaml"))));
                } else {
                    merged.put(name, v);
                }
            }
            // keys not in metadata are dropped (orphan cf_ columns / stale jsonb keys)
        }
        ValidationResult result = new CustomFieldTypeValidator()
            .validate(metadata, merged, CustomFieldTypeValidator.Mode.READ);
        return result.normalized();
    }
}
