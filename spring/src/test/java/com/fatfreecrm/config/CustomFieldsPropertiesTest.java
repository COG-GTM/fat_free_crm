package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code ffcrm.custom-fields.*} defaults and the positive batch-size guard the backfill job relies on. */
class CustomFieldsPropertiesTest {

    @Test
    void missingValuesFallBackToDocumentedDefaults() {
        CustomFieldsProperties properties = new CustomFieldsProperties(null, null);

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backfill().batchSize()).isEqualTo(10_000);
    }

    @Test
    void nestedBatchSizeDefaultsIndependentlyOfTtl() {
        CustomFieldsProperties properties = new CustomFieldsProperties(
            Duration.ofMinutes(5), new CustomFieldsProperties.Backfill(null));

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backfill().batchSize()).isEqualTo(10_000);
    }

    @Test
    void explicitValuesAreKept() {
        CustomFieldsProperties properties = new CustomFieldsProperties(
            Duration.ZERO, new CustomFieldsProperties.Backfill(1));

        assertThat(properties.registryTtl()).isEqualTo(Duration.ZERO);
        assertThat(properties.backfill().batchSize()).isEqualTo(1);
    }

    @Test
    void nonPositiveBatchSizeIsRejected() {
        assertThatThrownBy(() -> new CustomFieldsProperties.Backfill(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
        assertThatThrownBy(() -> new CustomFieldsProperties.Backfill(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
