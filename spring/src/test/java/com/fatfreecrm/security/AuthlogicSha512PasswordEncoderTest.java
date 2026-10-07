package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fatfreecrm.support.LegacyAuthFixtures;
import com.fatfreecrm.support.LegacyAuthFixtures.LegacyUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;

class AuthlogicSha512PasswordEncoderTest {

    private final AuthlogicSha512PasswordEncoder encoder = new AuthlogicSha512PasswordEncoder(20);

    @Test
    void fixtureDeclaresTheRailsEncryptorAndProductionStretchCount() {
        assertThat(LegacyAuthFixtures.encryptor()).isEqualTo("authlogic_sha512");
        assertThat(LegacyAuthFixtures.stretches()).isEqualTo(20);
    }

    @Test
    void reproducesEachRailsPasswordDigestByteForByte() {
        for (LegacyUser user : LegacyAuthFixtures.users()) {
            String encoded = user.encryptedPassword() + "$" + user.passwordSalt();
            assertThat(encoder.matches(user.password(), encoded)).isTrue();
            assertThat(encoder.digest(user.password(), user.passwordSalt())).isEqualTo(user.encryptedPassword());
            assertThat(encoder.matches(user.password() + "wrong", encoded)).isFalse();
        }
    }

    @Test
    void rejectsBlankHashesAndMalformedEncodingsButAllowsAnEmptySalt() {
        assertThat(encoder.matches("password", "$salt")).isFalse();
        assertThat(encoder.matches("password", "hash-without-separator")).isFalse();
        assertThat(encoder.matches("password", null)).isFalse();
        String emptySaltHash = encoder.digest("password", "");
        assertThat(encoder.matches("password", emptySaltHash + "$")).isTrue();
    }

    @Test
    void encodesWithA20CharacterUrlSafeSaltAndNeverRequestsAnUpgrade() {
        String encoded = encoder.encode("password");
        assertThat(encoded).matches("[0-9a-f]{128}\\$[A-Za-z0-9_-]{20}");
        assertThat(encoder.matches("password", encoded)).isTrue();
        assertThat(encoder.upgradeEncoding(encoded)).isFalse();
    }

    @Test
    void matchesAnIndependentSingleRoundSha512Vector() throws Exception {
        byte[] input = "passwordsalt".getBytes(StandardCharsets.UTF_8);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(input));
        assertThat(new AuthlogicSha512PasswordEncoder(1).digest("password", "salt")).isEqualTo(expected);
    }

    @Test
    void delegatesBcryptAndLegacyPrefixedHashes() {
        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("bcrypt", Map.of(
            "bcrypt", new BCryptPasswordEncoder(),
            "authlogic-sha512", encoder
        ));
        String bcrypt = delegating.encode("x");
        assertThat(bcrypt).startsWith("{bcrypt}");
        assertThat(delegating.matches("x", bcrypt)).isTrue();

        LegacyUser user = LegacyAuthFixtures.users().getFirst();
        String legacy = "{authlogic-sha512}" + user.encryptedPassword() + "$" + user.passwordSalt();
        assertThat(delegating.matches(user.password(), legacy)).isTrue();
    }
}
