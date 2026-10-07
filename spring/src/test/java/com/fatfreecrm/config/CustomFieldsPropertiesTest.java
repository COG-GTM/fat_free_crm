package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CustomFieldsPropertiesTest {

    @Test
    void defaultsRegistryTtlAndBackfillWhenUnset() {
        CustomFieldsProperties properties = new CustomFieldsProperties(null, null);

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backfill().batchSize()).isEqualTo(10_000);
    }

    @Test
    void keepsExplicitValues() {
        CustomFieldsProperties properties = new CustomFieldsProperties(
            Duration.ofMinutes(5), new CustomFieldsProperties.Backfill(250));

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backfill().batchSize()).isEqualTo(250);
    }

    @Test
    void rejectsNonPositiveBatchSizes() {
        assertThatThrownBy(() -> new CustomFieldsProperties.Backfill(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batch size");
        assertThatThrownBy(() -> new CustomFieldsProperties.Backfill(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CustomFieldsProperties.Backfill(1).batchSize()).isEqualTo(1);
    }
}
