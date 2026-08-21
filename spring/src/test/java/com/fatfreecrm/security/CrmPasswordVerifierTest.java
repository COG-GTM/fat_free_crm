package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.RailsUserFixtures;
import com.fatfreecrm.support.RailsUserFixtures.LegacyUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class CrmPasswordVerifierTest {

    private static final int CONFIGURED_STRETCHES = 20;

    private final AuthlogicSha512PasswordEncoder legacyEncoder =
        new AuthlogicSha512PasswordEncoder(CONFIGURED_STRETCHES);
    private final PasswordEncoder delegating = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final CrmPasswordVerifier verifier = new CrmPasswordVerifier(delegating, legacyEncoder);

    @Test
    void verifiesBareRailsHashesWithThePerUserSalt() {
        LegacyUser user = RailsUserFixtures.users().getFirst();

        assertThat(verifier.matches(user.password(), user.encryptedPassword(), user.passwordSalt())).isTrue();
        assertThat(verifier.matches("wrong", user.encryptedPassword(), user.passwordSalt())).isFalse();
    }

    @Test
    void verifiesPrefixedHashesWrittenBySpring() {
        String encoded = delegating.encode("brand-new-password");

        assertThat(encoded).startsWith("{bcrypt}");
        assertThat(verifier.matches("brand-new-password", encoded, null)).isTrue();
        assertThat(verifier.matches("wrong", encoded, null)).isFalse();
    }

    @Test
    void treatsMissingHashesAsAFailedMatchRatherThanAnError() {
        assertThat(verifier.matches("anything", null, "salt")).isFalse();
        assertThat(verifier.matches("anything", "", "salt")).isFalse();
    }

    @Test
    void encodesNewPasswordsWithTheModernEncoderNotTheLegacyOne() {
        assertThat(verifier.encode("x")).startsWith("{bcrypt}");
    }
}
