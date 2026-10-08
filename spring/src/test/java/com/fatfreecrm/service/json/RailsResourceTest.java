package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link RailsResource} guards the table name that is interpolated into {@code RailsRowRepository} SQL. */
class RailsResourceTest {

    private static RailsResource resource(String table) {
        return new RailsResource("Account", table, Account.class, Set.of(), false, Set.of(),
            "accounts", Object::toString, Map.of());
    }

    @Test
    void acceptsLowercaseSnakeCaseTables() {
        assertThat(resource("accounts").table()).isEqualTo("accounts");
        assertThat(resource("account_contacts2").table()).isEqualTo("account_contacts2");
    }

    @Test
    void rejectsTableNamesThatCouldEscapeTheSqlIdentifier() {
        for (String table : new String[] {"Accounts", "accounts; drop table users", "public.accounts", "\"accounts\"",
            "1accounts", "_accounts", "", "accounts-archive", "accounts "}) {
            assertThatThrownBy(() -> resource(table))
                .as(table)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Rails table name");
        }
    }

    @Test
    void copiesCollectionsSoLaterMutationOfInputsDoesNotLeakIntoTheResource() {
        Set<String> yaml = new HashSet<>(Set.of("subscribed_users"));
        Set<String> excluded = new HashSet<>(Set.of("password"));
        Map<String, RailsResource.RelatedExclusion> related = new HashMap<>();
        related.put("campaign", new RailsResource.RelatedExclusion(id -> Set.of()));

        RailsResource resource = new RailsResource("Account", "accounts", Account.class, yaml, true, excluded,
            "accounts", Object::toString, related);
        yaml.add("extra");
        excluded.clear();
        related.clear();

        assertThat(resource.yamlArrayColumns()).containsExactly("subscribed_users");
        assertThat(resource.excludedColumns()).containsExactly("password");
        assertThat(resource.relatedExclusions()).containsOnlyKeys("campaign");
        assertThatThrownBy(() -> resource.yamlArrayColumns().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
