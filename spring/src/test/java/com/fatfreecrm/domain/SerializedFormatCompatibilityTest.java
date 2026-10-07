package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.support.RailsBase64;
import com.fatfreecrm.domain.support.SubscribedUsersConverter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SerializedFormatCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubscribedUsersConverter subscribedUsersConverter = new SubscribedUsersConverter();

    @Test
    void matchesRailsGoldenSerializedFormats() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/db/rails/serialized_formats.json")) {
            assertThat(stream).isNotNull();
            JsonNode golden = objectMapper.readTree(stream);
            for (JsonNode entry : golden.path("subscribed_users")) {
                List<Long> value = new ArrayList<>();
                entry.path("value").forEach(item -> value.add(item.longValue()));
                String serialized = entry.path("serialized").isNull()
                    ? null : entry.path("serialized").textValue();
                assertThat(subscribedUsersConverter.convertToDatabaseColumn(value)).isEqualTo(serialized);
                assertThat(subscribedUsersConverter.convertToEntityAttribute(serialized))
                    .containsExactlyElementsOf(value);
            }
            for (JsonNode entry : golden.path("subscribed_users_legacy_reads")) {
                List<Long> value = objectMapper.convertValue(
                    entry.path("value"), objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class)
                );
                assertThat(subscribedUsersConverter.convertToEntityAttribute(entry.path("serialized").textValue()))
                    .containsExactlyElementsOf(value);
            }
            for (JsonNode entry : golden.path("preferences")) {
                String json = entry.path("json").textValue();
                assertThat(RailsBase64.encode64(json)).isEqualTo(entry.path("serialized").textValue());
                assertThat(RailsBase64.decode64(entry.path("serialized").textValue())).isEqualTo(json);
            }
        }
    }
}
