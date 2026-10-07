package com.fatfreecrm.service.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatfreecrm.domain.Preference;
import com.fatfreecrm.domain.support.RailsBase64;
import com.fatfreecrm.repository.PreferenceRepository;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class UserPreferenceService {

    private final PreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;

    public UserPreferenceService(PreferenceRepository preferenceRepository, ObjectMapper objectMapper) {
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
    }

    public ListDefaults listDefaults(long userId, String controllerName) {
        Integer perPage = read(userId, controllerName + "_per_page")
            .map(UserPreferenceService::integer)
            .orElse(null);
        String sortBy = read(userId, controllerName + "_sort_by")
            .filter(JsonNode::isTextual)
            .map(JsonNode::asText)
            .orElse(null);
        return new ListDefaults(perPage, sortBy);
    }

    private Optional<JsonNode> read(long userId, String name) {
        try {
            return preferenceRepository.findFirstByUserIdAndNameOrderByIdAsc(userId, name)
                .map(Preference::getValue)
                .filter(value -> value != null)
                .map(RailsBase64::decode64)
                .map(this::parse);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static Integer integer(JsonNode value) {
        try {
            if (value.isIntegralNumber() && value.canConvertToInt()) {
                int parsed = value.intValue();
                return parsed > 0 ? parsed : null;
            }
            if (value.isTextual()) {
                int parsed = Integer.parseInt(value.asText());
                return parsed > 0 ? parsed : null;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    public record ListDefaults(Integer perPage, String sortBy) {
    }
}
