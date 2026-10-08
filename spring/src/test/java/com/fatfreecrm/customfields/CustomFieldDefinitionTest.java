package com.fatfreecrm.customfields;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.domain.support.RailsModelType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link CustomFieldDefinition} mirrors a {@code fields} row: Rails stores {@code disabled}/{@code required}
 * as nullable booleans (treated as false) and {@code collection}/{@code settings} as nullable YAML
 * (treated as empty), and a cached definition must not be mutable through its inputs or getters.
 */
class CustomFieldDefinitionTest {

    @Test
    void nullBooleansAndCollectionsNormalizeToRailsFalseAndEmpty() {
        CustomFieldDefinition definition = new CustomFieldDefinition(
            1L, "CustomField", 2L, RailsModelType.CONTACT, null, null, "cf_x", "X", null, null, "string",
            null, null, null, null, null, null, null);

        assertThat(definition.disabled()).isFalse();
        assertThat(definition.required()).isFalse();
        assertThat(definition.collection()).isEmpty();
        assertThat(definition.settings()).isEmpty();
        assertThat(definition.minlength()).isNull();
        assertThat(definition.maxlength()).isNull();
        assertThat(definition.pairId()).isNull();
    }

    @Test
    void collectionAndSettingsAreDefensivelyCopiedAndUnmodifiable() {
        List<String> collection = new ArrayList<>(List.of("a", "b"));
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("date_format", "%Y"));
        CustomFieldDefinition definition = new CustomFieldDefinition(
            1L, "CustomField", 2L, RailsModelType.ACCOUNT, 1, 1, "cf_x", "X", null, null, "select",
            collection, true, true, 1, 5, 9L, settings);

        collection.add("c");
        settings.put("extra", "value");

        assertThat(definition.collection()).containsExactly("a", "b");
        assertThat(definition.settings()).containsOnlyKeys("date_format");
        assertThatThrownBy(() -> definition.collection().add("d"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> definition.settings().put("k", "v"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(definition.disabled()).isTrue();
        assertThat(definition.required()).isTrue();
    }

    @Test
    void shortConstructorBuildsAnEnabledAccountCustomField() {
        CustomFieldDefinition definition = new CustomFieldDefinition(
            "cf_short", "Short", "integer", true, 2, 4, List.of("1", "2"), 7L, 8L);

        assertThat(definition.id()).isEqualTo(7L);
        assertThat(definition.pairId()).isEqualTo(8L);
        assertThat(definition.type()).isEqualTo("CustomField");
        assertThat(definition.klass()).isEqualTo(RailsModelType.ACCOUNT);
        assertThat(definition.fieldGroupId()).isNull();
        assertThat(definition.disabled()).isFalse();
        assertThat(definition.required()).isTrue();
        assertThat(definition.minlength()).isEqualTo(2);
        assertThat(definition.maxlength()).isEqualTo(4);
        assertThat(definition.collection()).containsExactly("1", "2");
        assertThat(definition.settings()).isEmpty();
    }

    @Test
    void equalityIsValueBasedAcrossIndependentCopies() {
        CustomFieldDefinition first = new CustomFieldDefinition(
            "cf_eq", "Eq", "string", false, null, null, new ArrayList<>(List.of("x")), 1L, null);
        CustomFieldDefinition second = new CustomFieldDefinition(
            "cf_eq", "Eq", "string", false, null, null, List.of("x"), 1L, null);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }
}
