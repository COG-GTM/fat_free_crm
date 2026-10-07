package com.fatfreecrm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FixtureUsersTest {

    @Test
    void mirrorsTheRailsContractFixtureUsers() throws Exception {
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        assertEquals(Set.of("admin", "alice", "bob", "sam", "carol"), users.keySet());

        List<String> byId = List.of("admin", "alice", "bob", "sam", "carol");
        for (int index = 0; index < byId.size(); index++) {
            FixtureUsers.FixtureUser user = users.get(byId.get(index));
            assertEquals(byId.get(index), user.key());
            assertEquals(index + 1, user.id());
            assertEquals(byId.get(index), user.username());
            assertEquals(byId.get(index) + "@contract.example", user.email());
            assertEquals("contract-password", user.password());
        }
        assertEquals(List.of("admin"), users.values().stream().filter(FixtureUsers.FixtureUser::admin)
            .map(FixtureUsers.FixtureUser::key).toList());
        assertEquals(List.of("sam"), users.values().stream().filter(FixtureUsers.FixtureUser::suspended)
            .map(FixtureUsers.FixtureUser::key).toList());
    }

    @Test
    void loadedUsersAreImmutable() throws Exception {
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        FixtureUsers.FixtureUser carol = users.get("carol");
        assertThrows(UnsupportedOperationException.class, () -> users.put("zed", carol));
        assertThrows(UnsupportedOperationException.class, () -> users.remove("carol"));
    }

    @Test
    void everyCheckedInCaseReferencesAKnownActiveFixtureUser() throws Exception {
        Map<String, FixtureUsers.FixtureUser> users = FixtureUsers.load();
        List<ContractCase> cases = CaseLoader.load();

        assertEquals(cases.size(), cases.stream().map(ContractCase::id).distinct().count());
        for (ContractCase contractCase : cases) {
            if (contractCase.auth().equals("anonymous")) {
                continue;
            }
            assertTrue(users.containsKey(contractCase.auth()),
                contractCase.id() + " references unknown fixture user " + contractCase.auth());
            assertFalse(users.get(contractCase.auth()).suspended(),
                contractCase.id() + " authenticates as the suspended fixture user");
        }
    }
}
