package com.fatfreecrm.service.vcard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VCardWriterTest {

    private final VCardWriter writer = new VCardWriter();

    @Test
    void treatsNullLastNameAsAnEmptyRubyInterpolation() {
        String body = writer.write(new VCardWriter.LeadData(
            "Ada", null, null, null, null, null, null, null));

        assertThat(body).contains("N:;Ada;;;\n", "FN:Ada \n");
        assertThat(writer.contentDisposition("Ada", null))
            .isEqualTo("attachment; filename=\"Ada .vcf\"; filename*=UTF-8''Ada%20.vcf");
    }
}
