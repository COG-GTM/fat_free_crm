package com.fatfreecrm.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Verifies a raw password against whatever hash format the row holds.
 *
 * <p>Rails writes bare hex SHA-512 digests with no algorithm marker, so the
 * marker's absence is what identifies a legacy hash. Anything written by this
 * service is prefixed by Spring's {@link PasswordEncoder} delegation scheme
 * ({@code {bcrypt}...}) and verified by {@code delegatingEncoder}.
 */
@Component
public class CrmPasswordVerifier {

    private final PasswordEncoder delegatingEncoder;
    private final AuthlogicSha512PasswordEncoder legacyEncoder;

    public CrmPasswordVerifier(PasswordEncoder delegatingEncoder, AuthlogicSha512PasswordEncoder legacyEncoder) {
        this.delegatingEncoder = delegatingEncoder;
        this.legacyEncoder = legacyEncoder;
    }

    public boolean matches(CharSequence rawPassword, String encodedPassword, String salt) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }
        if (isLegacyHash(encodedPassword)) {
            return legacyEncoder.matches(rawPassword, encodedPassword, salt);
        }
        return delegatingEncoder.matches(rawPassword, encodedPassword);
    }

    public boolean isLegacyHash(String encodedPassword) {
        return encodedPassword != null && !encodedPassword.startsWith("{");
    }

    public String encode(CharSequence rawPassword) {
        return delegatingEncoder.encode(rawPassword);
    }
}
