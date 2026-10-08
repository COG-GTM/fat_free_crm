package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.support.RailsModelType;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Only the six Rails models that {@code include FatFreeCRM::Fields} own a custom-fields table; any other
 * {@link RailsModelType} must be rejected rather than mapped to a guessed table.
 */
class CustomFieldRegistryTableNameTest {

    private static final Map<RailsModelType, String> TABLES = Map.of(
        RailsModelType.ACCOUNT, "accounts",
        RailsModelType.CAMPAIGN, "campaigns",
        RailsModelType.CONTACT, "contacts",
        RailsModelType.LEAD, "leads",
        RailsModelType.OPPORTUNITY, "opportunities",
        RailsModelType.TASK, "tasks");

    @Test
    void mapsEachFieldsBearingModelToItsRailsTable() {
        TABLES.forEach((type, table) ->
            assertThat(CustomFieldRegistry.tableName(type)).as(type.name()).isEqualTo(table));
    }

    @Test
    void rejectsEveryModelWithoutCustomFields() {
        for (RailsModelType type : EnumSet.complementOf(EnumSet.copyOf(TABLES.keySet()))) {
            assertThatThrownBy(() -> CustomFieldRegistry.tableName(type))
                .as(type.name())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(type.name());
        }
    }
}
