package com.fatfreecrm.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CustomFieldsPropertiesTest {

    @Test
    void appliesTheDocumentedDefaultsWhenNothingIsConfigured() {
        CustomFieldsProperties properties = new CustomFieldsProperties(null, null);

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.backfill().batchSize()).isEqualTo(10_000);
    }

    @Test
    void keepsConfiguredValues() {
        CustomFieldsProperties properties = new CustomFieldsProperties(
            Duration.ofMinutes(5), new CustomFieldsProperties.Backfill(250));

        assertThat(properties.registryTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backfill().batchSize()).isEqualTo(250);
    }

    @Test
    void defaultsTheBatchSizeWhenOnlyTheBackfillSectionIsPresent() {
        assertThat(new CustomFieldsProperties.Backfill(null).batchSize()).isEqualTo(10_000);
    }

    @Test
    void rejectsNonPositiveBatchSizes() {
        for (int batchSize : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> new CustomFieldsProperties.Backfill(batchSize))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new CustomFieldsProperties.Backfill(1).batchSize()).isEqualTo(1);
    }
}
