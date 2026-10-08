package com.fatfreecrm.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
