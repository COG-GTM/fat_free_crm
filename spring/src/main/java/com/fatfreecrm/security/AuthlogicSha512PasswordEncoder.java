package com.fatfreecrm.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class AuthlogicSha512PasswordEncoder implements PasswordEncoder {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] SALT_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private final int stretches;

    public AuthlogicSha512PasswordEncoder(int stretches) {
        if (stretches < 1) {
            throw new IllegalArgumentException("stretches must be at least 1");
        }
        this.stretches = stretches;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        byte[] randomBytes = new byte[20];
        SECURE_RANDOM.nextBytes(randomBytes);
        StringBuilder salt = new StringBuilder(randomBytes.length);
        for (byte randomByte : randomBytes) {
            salt.append(SALT_ALPHABET[(randomByte & 0xff) % SALT_ALPHABET.length]);
        }
        String saltValue = salt.substring(0, 20);
        return digest(rawPassword.toString(), saltValue) + "$" + saltValue;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        int separator = encodedPassword.indexOf('$');
        if (separator <= 0) {
            return false;
        }
        String storedHash = encodedPassword.substring(0, separator);
        String salt = encodedPassword.substring(separator + 1);
        String computedHash = digest(rawPassword.toString(), salt);
        return MessageDigest.isEqual(
            computedHash.getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return false;
    }

    public String digest(String rawPassword, String salt) {
        MessageDigest sha512 = sha512();
        String digest = rawPassword + salt;
        for (int round = 0; round < stretches; round++) {
            digest = HexFormat.of().formatHex(sha512.digest(digest.getBytes(StandardCharsets.UTF_8)));
            sha512.reset();
        }
        return digest;
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-512 is required but unavailable", exception);
        }
    }
}
