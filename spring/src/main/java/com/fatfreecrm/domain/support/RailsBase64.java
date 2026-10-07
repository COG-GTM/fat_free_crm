package com.fatfreecrm.domain.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class RailsBase64 {

    private static final int LINE_LENGTH = 60;

    private RailsBase64() {
    }

    public static String encode64(String value) {
        byte[] bytes = Base64.getEncoder().encode(value.getBytes(StandardCharsets.UTF_8));
        String encoded = new String(bytes, StandardCharsets.US_ASCII);
        StringBuilder result = new StringBuilder(encoded.length() + encoded.length() / LINE_LENGTH + 1);
        for (int start = 0; start < encoded.length(); start += LINE_LENGTH) {
            result.append(encoded, start, Math.min(start + LINE_LENGTH, encoded.length())).append('\n');
        }
        return result.toString();
    }

    public static String decode64(String value) {
        return new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
