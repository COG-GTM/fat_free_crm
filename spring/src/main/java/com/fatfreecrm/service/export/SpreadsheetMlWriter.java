package com.fatfreecrm.service.export;

import java.util.List;

/** The Builder output of app/views/layouts/header.xls.builder wrapping an app/views/x/index.xls.builder. */
public final class SpreadsheetMlWriter {

    private static final String WORKBOOK = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<Workbook xmlns:x=\"urn:schemas-microsoft-com:office:excel\" "
        + "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\" "
        + "xmlns:html=\"http://www.w3.org/TR/REC-html40\" "
        + "xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" "
        + "xmlns:o=\"urn:schemas-microsoft-com:office:office\">\n";

    private SpreadsheetMlWriter() {
    }

    /** {@code withHeader} mirrors the templates' {@code unless @records.empty?} guard. */
    public static String write(String worksheet, List<String> header, List<List<Object>> rows, boolean withHeader) {
        StringBuilder xml = new StringBuilder(WORKBOOK);
        xml.append("<Worksheet ss:Name=\"").append(escapeAttribute(worksheet)).append("\">\n");
        xml.append("  <Table>\n");
        if (withHeader) {
            appendRow(xml, List.copyOf(header), true);
            rows.forEach(row -> appendRow(xml, row, false));
        }
        xml.append("  </Table>\n</Worksheet>\n</Workbook>\n");
        return xml.toString();
    }

    private static void appendRow(StringBuilder xml, List<?> row, boolean header) {
        xml.append("    <Row>\n");
        for (Object value : row) {
            String type = !header && RubyFormat.numeric(value) ? "Number" : "String";
            xml.append("      <Cell>\n        <Data ss:Type=\"").append(type).append('"');
            String text = RubyFormat.toS(value);
            if (text == null) {
                xml.append("/>\n");
            } else {
                xml.append('>').append(escapeText(text)).append("</Data>\n");
            }
            xml.append("      </Cell>\n");
        }
        xml.append("    </Row>\n");
    }

    static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String escapeAttribute(String text) {
        return escapeText(text).replace("\"", "&quot;");
    }
}
