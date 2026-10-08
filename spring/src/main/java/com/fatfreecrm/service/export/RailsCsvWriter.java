package com.fatfreecrm.service.export;

import java.util.List;

/** Ruby {@code CSV.generate} default dialect: comma, {@code "} quoting when needed, {@code \n}, no BOM. */
public final class RailsCsvWriter {

    private RailsCsvWriter() {
    }

    public static String write(List<String> header, List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder csv = new StringBuilder();
        appendRow(csv, List.copyOf(header));
        rows.forEach(row -> appendRow(csv, row));
        return csv.toString();
    }

    private static void appendRow(StringBuilder csv, List<?> row) {
        for (int index = 0; index < row.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            String field = RubyFormat.toS(row.get(index));
            if (field == null) {
                continue;
            }
            if (field.isEmpty() || field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                    || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
                csv.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                csv.append(field);
            }
        }
        csv.append('\n');
    }
}
