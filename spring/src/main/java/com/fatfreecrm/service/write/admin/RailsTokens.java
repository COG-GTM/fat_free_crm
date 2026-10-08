package com.fatfreecrm.service.write.admin;

import java.security.SecureRandom;
import java.util.Base64;

/** {@code Devise.friendly_token}: 15 random bytes, url-safe Base64, {@code tr('lIO0', 'sxyz')}. */
final class RailsTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RailsTokens() {
    }

    static String friendlyToken() {
        byte[] bytes = new byte[15];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return token.replace('l', 's').replace('I', 'x').replace('O', 'y').replace('0', 'z');
    }
}
