package com.fatfreecrm.service.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.Account;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The table name is interpolated into SQL by {@code RailsRowRepository}, so the descriptor must reject anything unsafe. */
class RailsResourceTest {

    private static RailsResource resource(String table) {
        return new RailsResource(
            "Account", table, Account.class, Set.of(), true, Set.of(), "accounts", ignored -> "", Map.of());
    }

    @Test
    void acceptsRailsStyleTableNames() {
        assertThat(resource("accounts").table()).isEqualTo("accounts");
        assertThat(resource("account_contacts").table()).isEqualTo("account_contacts");
        assertThat(resource("v1_rows").table()).isEqualTo("v1_rows");
    }

    @Test
    void rejectsTableNamesThatCouldAlterTheQuery() {
        for (String table : new String[] {
            "", "Accounts", "1accounts", "_accounts", "accounts;", "accounts; DROP TABLE users",
            "public.accounts", "accounts--", "\"accounts\"", "accounts WHERE 1=1", " accounts"
        }) {
            assertThatThrownBy(() -> resource(table))
                .as("table %s", table)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Rails table name");
        }
    }

    @Test
    void takesDefensiveCopiesOfCollections() {
        Set<String> yamlColumns = new HashSet<>(Set.of("subscribed_users"));
        Set<String> excluded = new HashSet<>(Set.of("custom_fields"));
        Map<String, RailsResource.RelatedExclusion> exclusions = new HashMap<>();
        exclusions.put("users", new RailsResource.RelatedExclusion(ignored -> Set.of()));

        RailsResource resource = new RailsResource(
            "Account", "accounts", Account.class, yamlColumns, true, excluded, "accounts", ignored -> "", exclusions);
        yamlColumns.add("other");
        excluded.clear();
        exclusions.clear();

        assertThat(resource.yamlArrayColumns()).containsExactly("subscribed_users");
        assertThat(resource.excludedColumns()).containsExactly("custom_fields");
        assertThat(resource.relatedExclusions()).containsOnlyKeys("users");
        assertThatThrownBy(() -> resource.yamlArrayColumns().add("x"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resource.relatedExclusions().remove("users"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void crmResourcesExcludeTheRailsIgnoredCustomFieldsColumn() {
        assertThat(RailsResources.CRM_EXCLUDED_COLUMNS).containsExactly("custom_fields");
    }
}
