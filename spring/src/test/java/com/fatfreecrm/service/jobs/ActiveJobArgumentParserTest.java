package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ActiveJobArgumentParserTest {

    private final ActiveJobArgumentParser parser = new ActiveJobArgumentParser(new ObjectMapper());

    @Test
    void parsesRailsGlobalIdAndPrimitiveJobArguments() {
        assertEquals(42, parser.accountId("[{\"_aj_globalid\":\"gid://fat_free_crm/Account/42\"}]"));
        assertEquals(7, parser.accountId("[{\"account_id\":7}]"));
        assertEquals(13, parser.accountId("[13]"));
    }

    @Test
    void parsesAccountGlobalIdFromRailsSolidQueueEnvelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode golden = mapper.readTree(getClass().getResourceAsStream("/mail/active_job_arguments.json"));
        String serializedJob = mapper.writeValueAsString(golden.path("account_website").path("solid_queue"));

        assertEquals(970003, parser.accountId(serializedJob));
    }
}
