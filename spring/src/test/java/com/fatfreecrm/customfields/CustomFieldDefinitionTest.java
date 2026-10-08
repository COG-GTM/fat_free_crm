package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.support.RailsModelType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rails {@code Field} rows store {@code disabled}/{@code required} as nullable booleans and settings as YAML. */
class CustomFieldDefinitionTest {

    @Test
    void treatsNullBooleansAndCollectionsLikeRailsBlankAttributes() {
        CustomFieldDefinition definition = new CustomFieldDefinition(
            1L, "CustomField", 2L, RailsModelType.CONTACT, null, null, "cf_region", "Region",
            null, null, "string", null, null, null, null, null, null, null);

        assertThat(definition.disabled()).isFalse();
        assertThat(definition.required()).isFalse();
        assertThat(definition.collection()).isEmpty();
        assertThat(definition.settings()).isEmpty();
        assertThat(definition.klass()).isEqualTo(RailsModelType.CONTACT);
    }

    @Test
    void copiesCollectionAndSettingsSoLaterMutationOfTheSourceIsIgnored() {
        List<String> collection = new ArrayList<>(List.of("North", "South"));
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("step", 1));
        CustomFieldDefinition definition = new CustomFieldDefinition(
            1L, "CustomField", 2L, RailsModelType.ACCOUNT, 1, 1, "cf_region", "Region",
            null, null, "select", collection, true, true, 1, 10, 7L, settings);
        collection.add("East");
        settings.put("step", 2);

        assertThat(definition.collection()).containsExactly("North", "South");
        assertThat(definition.settings()).containsEntry("step", 1);
        assertThat(definition.disabled()).isTrue();
        assertThat(definition.required()).isTrue();
        assertThat(definition.pairId()).isEqualTo(7L);
        assertThatThrownBy(() -> definition.collection().add("West"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.settings().put("x", 1))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shortConstructorDefaultsToAnEnabledAccountCustomField() {
        CustomFieldDefinition definition = new CustomFieldDefinition(
            "cf_notes", "Notes", "text", true, 2, 5, null, 9L, null);

        assertThat(definition.id()).isEqualTo(9L);
        assertThat(definition.type()).isEqualTo("CustomField");
        assertThat(definition.klass()).isEqualTo(RailsModelType.ACCOUNT);
        assertThat(definition.disabled()).isFalse();
        assertThat(definition.required()).isTrue();
        assertThat(definition.minlength()).isEqualTo(2);
        assertThat(definition.maxlength()).isEqualTo(5);
        assertThat(definition.collection()).isEmpty();
        assertThat(definition.pairId()).isNull();
    }
}
