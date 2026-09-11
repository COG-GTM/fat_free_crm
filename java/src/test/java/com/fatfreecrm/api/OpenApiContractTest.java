package com.fatfreecrm.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code src/main/resources/static/openapi.yaml} is documented as a verbatim copy of the frozen
 * contract {@code docs/migration/openapi.yaml}; the two must not drift. Skipped when the module
 * is built outside the monorepo checkout (no {@code docs/} directory above it).
 */
class OpenApiContractTest {

    @Test
    void servedContractIsVerbatimCopyOfFrozenContract() throws IOException {
        Optional<Path> frozen = locateFrozenContract();
        assumeTrue(frozen.isPresent(), "docs/migration/openapi.yaml not found above the module; skipping drift check");

        String served = new ClassPathResource("static/openapi.yaml").getContentAsString(StandardCharsets.UTF_8);
        String source = Files.readString(frozen.get(), StandardCharsets.UTF_8);

        assertThat(served).isEqualTo(source);
    }

    @Test
    void servedContractDescribesTheAccountAndContactResources() throws IOException {
        String served = new ClassPathResource("static/openapi.yaml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(served)
                .startsWith("openapi:")
                .contains("/accounts:")
                .contains("/accounts/{id}:")
                .contains("/contacts:")
                .contains("/contacts/{id}:")
                .contains("/accounts/auto_complete")
                .contains("/contacts/auto_complete");
    }

    private static Optional<Path> locateFrozenContract() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs").resolve("migration").resolve("openapi.yaml");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }
}
