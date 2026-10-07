package com.fatfreecrm.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ffcrm.custom-fields")
public record CustomFieldsProperties(Duration registryTtl, Backfill backfill) {

    public CustomFieldsProperties {
        registryTtl = registryTtl == null ? Duration.ofSeconds(30) : registryTtl;
        backfill = backfill == null ? new Backfill(null) : backfill;
    }

    public record Backfill(Integer batchSize) {

        public Backfill {
            if (batchSize == null) {
                batchSize = Integer.valueOf(10_000);
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("Custom-fields backfill batch size must be positive");
            }
        }
    }
}
