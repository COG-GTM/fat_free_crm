package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.support.RailsModelType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomFieldDefinitionTest {

    @Test
    void nullFlagsAndCollectionsNormalizeToRailsDefaults() {
        CustomFieldDefinition definition = new CustomFieldDefinition(
            1L, "CustomField", 2L, RailsModelType.ACCOUNT, 1, 1, "cf_x", "X", null, null, "string",
            null, null, null, null, null, null, null);

        assertThat(definition.disabled()).isFalse();
        assertThat(definition.required()).isFalse();
        assertThat(definition.collection()).isEmpty();
        assertThat(definition.settings()).isEmpty();
    }

    @Test
    void collectionAndSettingsAreDefensivelyCopiedAndReadOnly() {
        List<String> collection = new ArrayList<>(List.of("a", "b"));
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("k", "v"));
        CustomFieldDefinition definition = new CustomFieldDefinition(
            1L, "CustomField", 2L, RailsModelType.CONTACT, 1, 1, "cf_x", "X", null, null, "select",
            collection, true, true, 1, 2, null, settings);

        collection.add("c");
        settings.put("k2", "v2");

        assertThat(definition.collection()).containsExactly("a", "b");
        assertThat(definition.settings()).containsExactly(Map.entry("k", "v"));
        assertThat(definition.disabled()).isTrue();
        assertThat(definition.required()).isTrue();
        assertThatThrownBy(() -> definition.collection().add("z"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.settings().put("z", 1))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shortConstructorBuildsAnEnabledAccountCustomField() {
        CustomFieldDefinition definition = new CustomFieldDefinition(
            "cf_x", "X", "integer", true, 1, 5, List.of("one"), 7L, 9L);

        assertThat(definition.id()).isEqualTo(7L);
        assertThat(definition.type()).isEqualTo("CustomField");
        assertThat(definition.klass()).isEqualTo(RailsModelType.ACCOUNT);
        assertThat(definition.name()).isEqualTo("cf_x");
        assertThat(definition.as()).isEqualTo("integer");
        assertThat(definition.required()).isTrue();
        assertThat(definition.disabled()).isFalse();
        assertThat(definition.minlength()).isEqualTo(1);
        assertThat(definition.maxlength()).isEqualTo(5);
        assertThat(definition.collection()).containsExactly("one");
        assertThat(definition.pairId()).isEqualTo(9L);
        assertThat(definition.settings()).isEmpty();
    }
}
