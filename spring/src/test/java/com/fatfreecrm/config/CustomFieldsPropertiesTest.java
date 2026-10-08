package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CustomFieldsPropertiesTest {

    @Test
    void defaultsRegistryTtlAndBatchSizeWhenUnset() {
        CustomFieldsProperties properties = new CustomFieldsProperties(null, null);

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backfill().batchSize()).isEqualTo(10_000);
    }

    @Test
    void defaultsBatchSizeWhenOnlyTheBackfillBlockIsPresent() {
        CustomFieldsProperties properties = new CustomFieldsProperties(
            Duration.ofMinutes(5), new CustomFieldsProperties.Backfill(null));

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backfill().batchSize()).isEqualTo(10_000);
    }

    @Test
    void keepsExplicitValues() {
        CustomFieldsProperties properties = new CustomFieldsProperties(
            Duration.ZERO, new CustomFieldsProperties.Backfill(1));

        assertThat(properties.registryTtl()).isEqualTo(Duration.ZERO);
        assertThat(properties.backfill().batchSize()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveBatchSizes() {
        assertThatThrownBy(() -> new CustomFieldsProperties.Backfill(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batch size must be positive");
        assertThatThrownBy(() -> new CustomFieldsProperties.Backfill(-10_000))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
