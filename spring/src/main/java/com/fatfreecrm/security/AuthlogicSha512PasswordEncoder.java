package com.fatfreecrm.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Reproduces {@code devise-encryptable}'s {@code :authlogic_sha512} encryptor,
 * which Fat Free CRM configures in {@code config/initializers/devise.rb}:
 *
 * <pre>
 * digest = password + salt
 * stretches.times { digest = Digest::SHA512.hexdigest(digest) }
 * </pre>
 *
 * The pepper is deliberately unused — the Ruby encryptor ignores it as well, so
 * peppering here would break every existing hash. The salt comes from the
 * per-user {@code password_salt} column, so this encoder cannot implement
 * {@link PasswordEncoder} directly; use {@link #digest(CharSequence, String)} or
 * the salt-bound view returned by {@link #forSalt(String)}.
 */
public final class AuthlogicSha512PasswordEncoder {

    private final int stretches;

    public AuthlogicSha512PasswordEncoder(int stretches) {
        if (stretches < 1) {
            throw new IllegalArgumentException("stretches must be >= 1, got " + stretches);
        }
        this.stretches = stretches;
    }

    public int getStretches() {
        return stretches;
    }

    /** Computes the Devise {@code encrypted_password} value for a raw password and salt. */
    public String digest(CharSequence rawPassword, String salt) {
        MessageDigest sha512 = sha512();
        String digest = rawPassword.toString() + (salt == null ? "" : salt);
        for (int i = 0; i < stretches; i++) {
            byte[] hashed = sha512.digest(digest.getBytes(StandardCharsets.UTF_8));
            digest = HexFormat.of().formatHex(hashed);
            sha512.reset();
        }
        return digest;
    }

    public boolean matches(CharSequence rawPassword, String encryptedPassword, String salt) {
        if (encryptedPassword == null || encryptedPassword.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
            digest(rawPassword, salt).getBytes(StandardCharsets.UTF_8),
            encryptedPassword.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Binds this encoder to one user's salt so it can be handed to Spring
     * Security APIs that only know the {@link PasswordEncoder} contract.
     */
    public PasswordEncoder forSalt(String salt) {
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return digest(rawPassword, salt);
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return AuthlogicSha512PasswordEncoder.this.matches(rawPassword, encodedPassword, salt);
            }
        };
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is required by the JLS but unavailable", e);
        }
    }
}
