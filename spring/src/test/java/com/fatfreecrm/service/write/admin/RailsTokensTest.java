package com.fatfreecrm.service.write.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@code Devise.friendly_token}: 15 random bytes → 20 url-safe chars, with {@code lIO0} remapped. */
class RailsTokensTest {

    @Test
    void friendlyTokenIsTwentyUrlSafeCharactersWithoutAmbiguousGlyphs() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String token = RailsTokens.friendlyToken();
            assertThat(token).hasSize(20).matches("[A-Za-z0-9_-]{20}").doesNotContainPattern("[lIO0]");
            seen.add(token);
        }
        assertThat(seen).hasSize(500);
    }
}
