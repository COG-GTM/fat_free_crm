package com.fatfreecrm.service.vcard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public record VCardWriter() {

    private static final Pattern NON_ASCII_FILENAME = Pattern.compile("[^ A-Za-z0-9!#$+.^_`|~-]");
    private static final Pattern NON_RFC5987_FILENAME = Pattern.compile("[^A-Za-z0-9!#$&+.^_`|~-]");

    public String write(ContactData contact) {
        List<String> properties = baseProperties(
            contact.firstName(), contact.lastName(), contact.title(),
            contact.accountName() == null
                ? null
                : "ORG:" + contact.accountName() + ";" + rubyString(contact.department()),
            contact.email(), contact.altEmail(), contact.phone(), contact.mobile()
        );
        if (contact.address() != null) {
            properties.add(addressProperty(contact.address()));
        }
        return serialize(properties);
    }

    public String write(LeadData lead) {
        List<String> properties = baseProperties(
            lead.firstName(), lead.lastName(), lead.title(),
            present(lead.company()) ? "ORG:" + lead.company() : null,
            lead.email(), lead.altEmail(), lead.phone(), lead.mobile()
        );
        return serialize(properties);
    }

    public String contentDisposition(String firstName, String lastName) {
        String filename = rubyString(firstName) + " " + rubyString(lastName) + ".vcf";
        return "attachment; filename=\"" + escapeFilename(transliterate(filename), NON_ASCII_FILENAME)
            + "\"; filename*=UTF-8''" + escapeFilename(filename, NON_RFC5987_FILENAME);
    }

    private static List<String> baseProperties(
        String firstName,
        String lastName,
        String title,
        String organization,
        String email,
        String altEmail,
        String phone,
        String mobile
    ) {
        String first = rubyString(firstName);
        String last = rubyString(lastName);
        List<String> properties = new ArrayList<>();
        properties.add("N:" + last + ";" + first + ";;;");
        properties.add("FN:" + first + " " + last);
        if (present(title)) {
            properties.add("TITLE:" + title);
        }
        if (organization != null) {
            properties.add(organization);
        }
        if (present(email)) {
            properties.add("EMAIL;TYPE=[\"internet\", \"work\"]:" + email);
        }
        if (present(altEmail)) {
            properties.add("EMAIL;TYPE=[\"internet\", \"work\"]:" + altEmail);
        }
        if (present(phone)) {
            properties.add("TEL;TYPE=work:" + phone);
        }
        if (present(mobile)) {
            properties.add("TEL;TYPE=[\"cell\", \"voice\"]:" + mobile);
        }
        properties.add("NOTE:Exported from Fat Free CRM");
        return properties;
    }

    private static String addressProperty(AddressData address) {
        return "ADR;TYPE=work:" + rubyString(address.street1()) + ";"
            + rubyString(address.street2()) + ";" + rubyString(address.city()) + ";"
            + rubyString(address.state()) + ";" + rubyString(address.zipcode()) + ";"
            + rubyString(address.country());
    }

    private static String serialize(List<String> properties) {
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCARD");
        lines.add("VERSION:4.0");
        properties.forEach(property -> lines.add(fold(property)));
        lines.add("END:VCARD");
        return String.join("\n", lines) + "\n";
    }

    private static String fold(String value) {
        List<String> chunks = new ArrayList<>();
        int startCodePoint = 0;
        int chunkSize = 75;
        int length = value.codePointCount(0, value.length());
        while (length - startCodePoint >= chunkSize) {
            int start = value.offsetByCodePoints(0, startCodePoint);
            int end = value.offsetByCodePoints(start, chunkSize);
            chunks.add(value.substring(start, end));
            startCodePoint += chunkSize;
            chunkSize = 74;
        }
        chunks.add(value.substring(value.offsetByCodePoints(0, startCodePoint)));
        return String.join("\n ", chunks);
    }

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String rubyString(String value) {
        return value == null ? "" : value;
    }

    private static String transliterate(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder result = new StringBuilder();
        decomposed.codePoints().forEach(codePoint -> result.appendCodePoint(codePoint < 128 ? codePoint : '?'));
        return result.toString();
    }

    private static String escapeFilename(String filename, Pattern escapedCharacters) {
        StringBuilder escaped = new StringBuilder();
        for (byte value : filename.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int unsigned = value & 0xff;
            String character = new String(new byte[] {value}, java.nio.charset.StandardCharsets.UTF_8);
            if (unsigned < 128 && !escapedCharacters.matcher(character).matches()) {
                escaped.append(character);
            } else {
                escaped.append(String.format(Locale.ROOT, "%%%02X", unsigned));
            }
        }
        return escaped.toString();
    }

    public record AddressData(
        String street1,
        String street2,
        String city,
        String state,
        String zipcode,
        String country
    ) {
    }

    public record ContactData(
        String firstName,
        String lastName,
        String title,
        String accountName,
        String department,
        String email,
        String altEmail,
        String mobile,
        String phone,
        AddressData address
    ) { }

    public record LeadData(
        String firstName,
        String lastName,
        String title,
        String company,
        String email,
        String altEmail,
        String mobile,
        String phone
    ) { }
}
