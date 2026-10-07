package com.fatfreecrm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fatfreecrm.support.LegacyAuthFixtures;
import com.fatfreecrm.support.LegacyAuthFixtures.LegacyUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
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

    @Test
    void rejectsZeroOrNegativeStretchesLikeDeviseWhichAlwaysHashesAtLeastOnce() {
        assertThatThrownBy(() -> new AuthlogicSha512PasswordEncoder(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthlogicSha512PasswordEncoder(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverMatchesANullOrEmptyRawPasswordAgainstARealHash() {
        LegacyUser user = LegacyAuthFixtures.users().getFirst();
        String encoded = user.encryptedPassword() + "$" + user.passwordSalt();

        assertThat(encoder.matches(null, encoded)).isFalse();
        assertThat(encoder.matches("", encoded)).isFalse();
    }

    @Test
    void stretchCountChangesTheDigestSoTestAndProductionHashesAreNotInterchangeable() {
        LegacyUser user = LegacyAuthFixtures.users().getFirst();
        String encoded = user.encryptedPassword() + "$" + user.passwordSalt();

        assertThat(new AuthlogicSha512PasswordEncoder(1).matches(user.password(), encoded)).isFalse();
        assertThat(new AuthlogicSha512PasswordEncoder(19).matches(user.password(), encoded)).isFalse();
        assertThat(new AuthlogicSha512PasswordEncoder(21).matches(user.password(), encoded)).isFalse();
    }

    @Test
    void secondStretchIsTheSha512OfTheFirstRoundsHexDigest() throws Exception {
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        String first = HexFormat.of().formatHex(sha512.digest("passwordsalt".getBytes(StandardCharsets.UTF_8)));
        String second = HexFormat.of().formatHex(sha512.digest(first.getBytes(StandardCharsets.UTF_8)));

        assertThat(new AuthlogicSha512PasswordEncoder(2).digest("password", "salt")).isEqualTo(second);
    }

    @Test
    void comparesTheStoredHashCaseSensitivelyAndRejectsTruncatedHashes() {
        LegacyUser user = LegacyAuthFixtures.users().getFirst();

        String upper = user.encryptedPassword().toUpperCase(Locale.ROOT) + "$" + user.passwordSalt();
        assertThat(encoder.matches(user.password(), upper)).isFalse();
        String truncated = user.encryptedPassword().substring(0, 127) + "$" + user.passwordSalt();
        assertThat(encoder.matches(user.password(), truncated)).isFalse();
        String otherSalt = user.encryptedPassword() + "$" + LegacyAuthFixtures.users().get(1).passwordSalt();
        assertThat(encoder.matches(user.password(), otherSalt)).isFalse();
    }
}
