package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RailsJsonWriterYamlArrayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesYamlIntegersAsJsonIntegers() {
        assertThat(RailsJsonWriter.yamlArray("---\n- 2\n- 3\n", objectMapper).toString())
            .isEqualTo("[2,3]");
    }

    @Test
    void preservesYamlStringsAsJsonStrings() {
        assertThat(RailsJsonWriter.yamlArray("---\n- '7'\n- unknown\n", objectMapper).toString())
            .isEqualTo("[\"7\",\"unknown\"]");
    }

    @Test
    void mapsEmptyAndNullYamlValuesToEmptyArrays() {
        assertThat(RailsJsonWriter.yamlArray("", objectMapper).toString()).isEqualTo("[]");
        assertThat(RailsJsonWriter.yamlArray(null, objectMapper).toString()).isEqualTo("[]");
    }
}
