package com.fatfreecrm.api.dto;

import com.fatfreecrm.domain.Contact;
import java.util.ArrayList;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * {@link Contact} entity → {@link ContactDto}. All properties map by name; the only conversion
 * is the Rails-serialized {@code subscribed_users} YAML text → {@code List<Long>}, which
 * MapStruct routes through {@link #parseSubscribedUsers(String)} (the sole
 * {@code String → List<Long>} method on this mapper).
 */
@Mapper(config = CentralMapperConfig.class)
public interface ContactMapper {

    ContactDto toDto(Contact contact);

    /**
     * Decodes the YAML that Rails writes for {@code serialize :subscribed_users, type: Array}
     * when the array holds only integers:
     * <pre>
     * ---
     * - 1
     * - 2
     * </pre>
     * Accepted: {@code null}/blank, {@code --- []}, and a {@code ---} document header followed by
     * zero or more {@code - <integer>} lines (blank lines and trailing whitespace tolerated).
     * Anything else — nested structures, quoted or non-integer items, a missing header — is not
     * a form Rails produces for this column and yields an empty list instead of an error, so a
     * single odd row can never break a list response. This is intentionally not a YAML parser.
     */
    static List<Long> parseSubscribedUsers(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return List.of();
        }
        String[] lines = yaml.strip().split("\\r?\\n");
        String header = lines[0].strip();
        if (header.equals("--- []")) {
            return List.of();
        }
        if (!header.equals("---")) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(lines.length - 1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
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
