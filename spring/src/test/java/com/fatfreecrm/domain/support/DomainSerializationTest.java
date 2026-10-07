package com.fatfreecrm.domain.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainSerializationTest {

    private final AccessConverter accessConverter = new AccessConverter();
    private final RailsModelTypeConverter modelTypeConverter = new RailsModelTypeConverter();
    private final SubscribedUsersConverter subscribedUsersConverter = new SubscribedUsersConverter();

    @Test
    void convertsAccessNamesExactly() {
        for (Access access : Access.values()) {
            assertThat(accessConverter.convertToDatabaseColumn(access)).isEqualTo(access.railsValue());
            assertThat(accessConverter.convertToEntityAttribute(access.railsValue())).isEqualTo(access);
        }
        assertThat(accessConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(accessConverter.convertToEntityAttribute(null)).isNull();
        assertThatThrownBy(() -> accessConverter.convertToEntityAttribute("Lead"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Lead");
    }

    @Test
    void mapsRailsPolymorphicNamesAndRejectsUnknownValues() {
        assertThat(RailsModelType.values()).hasSize(26);
        assertThat(RailsModelType.fromRailsName("List")).isEqualTo(RailsModelType.LIST);
        assertThat(RailsModelType.LIST.entityClass()).isEqualTo(com.fatfreecrm.domain.SavedList.class);
        for (RailsModelType type : RailsModelType.values()) {
            assertThat(modelTypeConverter.convertToDatabaseColumn(type)).isEqualTo(type.railsName());
            assertThat(modelTypeConverter.convertToEntityAttribute(type.railsName())).isEqualTo(type);
        }
        assertThatThrownBy(() -> modelTypeConverter.convertToEntityAttribute("CustomField"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CustomField");
    }

    @Test
    void readsAndWritesOrderedSubscribedUserYaml() {
        assertThat(subscribedUsersConverter.convertToDatabaseColumn(null)).isNull();
        assertThat(subscribedUsersConverter.convertToDatabaseColumn(List.of())).isNull();
        assertThat(subscribedUsersConverter.convertToDatabaseColumn(List.of(3L, 1L, 2L)))
            .isEqualTo("---\n- 3\n- 1\n- 2\n");
        assertThat(subscribedUsersConverter.convertToEntityAttribute(null)).isEmpty();
        assertThat(subscribedUsersConverter.convertToEntityAttribute("--- []\n")).isEmpty();
        assertThat(subscribedUsersConverter.convertToEntityAttribute("---\n- 3\n- 1\n- 3\n"))
            .containsExactly(3L, 1L, 3L);
        assertThatThrownBy(() -> subscribedUsersConverter.convertToEntityAttribute("---\n- 1.5\n"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subscribedUsersConverter.convertToEntityAttribute("---\n- invalid\n"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesRubyBase64LineWrapping() {
        assertThat(RailsBase64.encode64("")).isEmpty();
        assertThat(RailsBase64.encode64("hello")).isEqualTo("aGVsbG8=\n");
        String input = "a".repeat(70);
        String encoded = RailsBase64.encode64(input);
        assertThat(encoded.lines().map(String::length)).containsExactly(60, 36);
        assertThat(encoded).endsWith("\n");
        assertThat(RailsBase64.decode64(encoded)).isEqualTo(input);
        assertThat(RailsBase64.decode64(RailsBase64.encode64("café-日本語-🔐"))).isEqualTo("café-日本語-🔐");
    }

    @Test
    void loadsRailsYamlTagsAsSafeUnderlyingValues() {
        String yaml = "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess\nname: value\nlist:\n- first\n";
        assertThat(RailsYaml.readStringMap(yaml)).containsEntry("name", "value")
            .containsEntry("list", List.of("first"));
        assertThat(RailsYaml.readStringList("---\n- :one\n- :two\n"))
            .containsExactly(":one", ":two");
        assertThat(RailsYaml.read("--- !ruby/object:Unsupported\nname: value\n"))
            .isEqualTo(Map.of("name", "value"));
    }
}
