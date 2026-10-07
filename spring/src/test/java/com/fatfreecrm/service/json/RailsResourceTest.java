package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The table name is interpolated into SQL, so the descriptor must reject anything but a plain identifier. */
class RailsResourceTest {

    private static RailsResource resource(String table) {
        return new RailsResource(
            "Account", table, Account.class, Set.of(), true, Set.of(), "accounts", ignored -> "", Map.of());
    }

    @Test
    void acceptsLowerCaseSnakeCaseTableNames() {
        assertThat(resource("accounts").table()).isEqualTo("accounts");
        assertThat(resource("account_contacts2").table()).isEqualTo("account_contacts2");
    }

    @Test
    void rejectsTableNamesThatAreNotPlainIdentifiers() {
        for (String table : new String[] {
            "", "Accounts", "1accounts", "_accounts", "accounts; DROP TABLE users",
            "accounts--", "public.accounts", "\"accounts\"", "accounts "}) {
            assertThatThrownBy(() -> resource(table))
                .as("table %s", table)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Rails table name");
        }
    }

    @Test
    void copiesMutableCollectionsDefensively() {
        Set<String> yaml = new HashSet<>(Set.of("subscribed_users"));
        Set<String> excluded = new HashSet<>(Set.of("custom_fields"));
        Map<String, RailsResource.RelatedExclusion> exclusions = new HashMap<>();
        exclusions.put("users", new RailsResource.RelatedExclusion(id -> Set.of()));

        RailsResource resource = new RailsResource(
            "Account", "accounts", Account.class, yaml, true, excluded, "accounts", ignored -> "", exclusions);
        yaml.add("later");
        excluded.add("later");
        exclusions.put("later", new RailsResource.RelatedExclusion(id -> Set.of()));

        assertThat(resource.yamlArrayColumns()).containsExactly("subscribed_users");
        assertThat(resource.excludedColumns()).containsExactly("custom_fields");
        assertThat(resource.relatedExclusions()).containsOnlyKeys("users");
        assertThatThrownBy(() -> resource.excludedColumns().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
