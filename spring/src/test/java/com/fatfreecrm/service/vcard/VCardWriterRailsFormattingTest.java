package com.fatfreecrm.service.vcard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link VCardWriter} line folding, optional properties and the Rails {@code send_data filename:}
 * header to the behaviour observed from {@code ContactsController#show.vcf} / {@code LeadsController#show.vcf}.
 */
class VCardWriterRailsFormattingTest {

    private static final String EMAIL_PREFIX = "EMAIL;TYPE=[\"internet\", \"work\"]:";

    private final VCardWriter writer = new VCardWriter();

    @Test
    void foldsLinesOfExactlySeventyFiveCodePointsWithAnEmptyContinuation() {
        String email = "e".repeat(75 - EMAIL_PREFIX.length());
        String body = writer.write(new VCardWriter.ContactData(
            "Exact", "75", null, null, null, email, null, null, null, null));

        assertThat(body).isEqualTo("BEGIN:VCARD\nVERSION:4.0\nN:75;Exact;;;\nFN:Exact 75\n"
            + EMAIL_PREFIX + email + "\n \nNOTE:Exported from Fat Free CRM\nEND:VCARD\n");
    }

    @Test
    void foldsByCodePointsNotUtf16UnitsAndContinuationsHoldSeventyFourCodePoints() {
        String email = "é😀".repeat(80);
        String body = writer.write(new VCardWriter.ContactData(
            "Zoë😀", "Multibyte", "Research😀", null, null, email, null, null, null, null));

        String[] lines = body.split("\n");
        String emailLine = lines[5];
        assertThat(emailLine).startsWith(EMAIL_PREFIX);
        assertThat(emailLine.codePointCount(0, emailLine.length())).isEqualTo(75);
        assertThat(lines[6]).startsWith(" ");
        assertThat(lines[6].codePointCount(0, lines[6].length())).isEqualTo(75);
        assertThat(lines[7]).startsWith(" ");
        String unfolded = emailLine + lines[6].substring(1) + lines[7].substring(1);
        assertThat(unfolded).isEqualTo(EMAIL_PREFIX + email);
        assertThat(lines[8]).isEqualTo("NOTE:Exported from Fat Free CRM");
    }

    @Test
    void shortLinesAreNeverFolded() {
        String email = "e".repeat(74 - EMAIL_PREFIX.length());
        String body = writer.write(new VCardWriter.ContactData(
            "Short", "Line", null, null, null, email, null, null, null, null));

        assertThat(body).contains(EMAIL_PREFIX + email + "\nNOTE:");
        assertThat(body).doesNotContain("\n \n");
    }

    @Test
    void contactWithAnAccountEmitsOrgEvenWhenNameAndDepartmentAreBlank() {
        String body = writer.write(new VCardWriter.ContactData(
            "Alan", "Nildept", "Scientist", "", null, "alan@example.test", null, null, "555-1003", null));

        assertThat(body).isEqualTo("BEGIN:VCARD\nVERSION:4.0\nN:Nildept;Alan;;;\nFN:Alan Nildept\nTITLE:Scientist\n"
            + "ORG:;\n" + EMAIL_PREFIX + "alan@example.test\nTEL;TYPE=work:555-1003\n"
            + "NOTE:Exported from Fat Free CRM\nEND:VCARD\n");
    }

    @Test
    void contactWithoutAnAccountOmitsOrg() {
        String body = writer.write(new VCardWriter.ContactData(
            "Grace", "Noaccount", null, null, null, null, null, null, null, null));

        assertThat(body).isEqualTo("BEGIN:VCARD\nVERSION:4.0\nN:Noaccount;Grace;;;\nFN:Grace Noaccount\n"
            + "NOTE:Exported from Fat Free CRM\nEND:VCARD\n");
    }

    @Test
    void leadOmitsOrgTitleEmailAndPhoneWhenBlankOrNull() {
        String withNulls = writer.write(new VCardWriter.LeadData(
            "Company", "Missing", null, null, null, null, null, null));
        String withBlanks = writer.write(new VCardWriter.LeadData(
            "Blank", "Lead", "", "", null, null, null, ""));

        assertThat(withNulls).isEqualTo("BEGIN:VCARD\nVERSION:4.0\nN:Missing;Company;;;\nFN:Company Missing\n"
            + "NOTE:Exported from Fat Free CRM\nEND:VCARD\n");
        assertThat(withBlanks).isEqualTo("BEGIN:VCARD\nVERSION:4.0\nN:Lead;Blank;;;\nFN:Blank Lead\n"
            + "NOTE:Exported from Fat Free CRM\nEND:VCARD\n");
    }

    @Test
    void contentDispositionTransliteratesNonAsciiAndEncodesTheUtf8Filename() {
        assertThat(writer.contentDisposition("Zoë😀", "Multibyte"))
            .isEqualTo("attachment; filename=\"Zoe%3F Multibyte.vcf\"; "
                + "filename*=UTF-8''Zo%C3%AB%F0%9F%98%80%20Multibyte.vcf");
        assertThat(writer.contentDisposition("Exact", "75"))
            .isEqualTo("attachment; filename=\"Exact 75.vcf\"; filename*=UTF-8''Exact%2075.vcf");
    }
}
