package com.fatfreecrm.service.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "The application ObjectMapper is intentionally retained by this parser."
)
public class ActiveJobArgumentParser {

    private final ObjectMapper objectMapper;

    public ActiveJobArgumentParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public long accountId(String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            JsonNode candidate = args.isArray() ? args.path(0) : args;
            if (candidate.isObject() && candidate.path("arguments").isArray()) {
                candidate = candidate.path("arguments").path(0);
            }
            if (candidate.isObject()) {
                for (String key : new String[] {"account_id", "id"}) {
                    if (candidate.hasNonNull(key) && candidate.path(key).canConvertToLong()) {
                        return candidate.path(key).asLong();
                    }
                }
                String globalId = candidate.path("_aj_globalid").asText(null);
                if (globalId != null) {
                    return Long.parseLong(globalId.substring(globalId.lastIndexOf('/') + 1));
                }
            } else if (candidate.isIntegralNumber()) {
                return candidate.asLong();
            }
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalArgumentException("Unable to parse Solid Queue Active Job arguments", exception);
        }
        throw new IllegalArgumentException("Unsupported Solid Queue Active Job argument shape");
    }
}
