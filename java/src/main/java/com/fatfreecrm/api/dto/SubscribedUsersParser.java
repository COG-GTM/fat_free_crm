package com.fatfreecrm.api.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the Rails-serialized {@code subscribed_users} YAML column into the
 * {@code array of integer} the OpenAPI schema promises.
 *
 * <p>Rails writes the column with {@code serialize :subscribed_users} (YAML). Only the trivial
 * array-of-integers form Rails actually produces is recognised:
 * <pre>
 * ---
 * - 1
 * - 2
 * </pre>
 * (leading {@code ---} document marker optional, one {@code - <integer>} per line, blank lines
 * ignored). {@code null}, blank text, an empty YAML array ({@code --- []}) and <em>anything else</em>
 * — flow sequences, non-integer items, nested structures, Ruby object tags — yield an empty list.
 * No YAML library is involved and unparseable text is never an error: the data-migration phase
 * owns proper conversion of this column (docs/migration/data-model.md, "Serialized columns").
 */
public final class SubscribedUsersParser {

    private static final String DOCUMENT_MARKER = "---";

    private SubscribedUsersParser() {
    }

    public static List<Long> parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        String[] lines = yaml.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (i == 0 && line.startsWith(DOCUMENT_MARKER)) {
                String rest = line.substring(DOCUMENT_MARKER.length()).strip();
                if (rest.isEmpty()) {
                    continue;
                }
                return List.of();
            }
            if (!line.startsWith("- ")) {
                return List.of();
            }
            try {
                ids.add(Long.parseLong(line.substring(2).strip()));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return List.copyOf(ids);
    }
}
