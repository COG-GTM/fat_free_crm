package com.fatfreecrm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RailsYamlIdListConverterTest {

    private final RailsYamlIdListConverter converter = new RailsYamlIdListConverter();

    @Test
    void writesExactlyWhatRailsYamlDumpWrites() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of())).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of(1L, 42L))).isEqualTo("---\n- 1\n- 42\n");
    }

    @Test
    void readsRailsAndLegacyYamlShapes() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("--- []\n")).isEmpty();
        assertThat(converter.convertToEntityAttribute("---\n- 1\n- 42\n")).containsExactly(1L, 42L);
        assertThat(converter.convertToEntityAttribute("--- [3, 4]\n")).containsExactly(3L, 4L);
        assertThat(converter.convertToEntityAttribute("---\n- '7'\n")).containsExactly(7L);
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }

    @Test
    void rejectsNonIntegerContent() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("--- bob\n"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("---\n- bob\n"))
            .isInstanceOf(NumberFormatException.class);
    }
}
