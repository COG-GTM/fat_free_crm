package com.fatfreecrm.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings. {@code legacyStretches} must match Devise's
 * {@code config.stretches} in the Rails deployment being migrated, otherwise no
 * existing password will verify.
 */
@ConfigurationProperties(prefix = "ffcrm.security")
public class SecurityProperties {

    private int legacyStretches = 20;

    /**
     * Re-hashing a legacy password to bcrypt on successful login would make the
     * row unreadable to the still-live Rails app, so it stays off until the
     * Phase 7 auth cutover.
     */
    private boolean rehashOnLogin;

    private final Jwt jwt = new Jwt();

    public int getLegacyStretches() {
        return legacyStretches;
    }

    public void setLegacyStretches(int legacyStretches) {
        this.legacyStretches = legacyStretches;
    }

    public boolean isRehashOnLogin() {
        return rehashOnLogin;
    }

    public void setRehashOnLogin(boolean rehashOnLogin) {
        this.rehashOnLogin = rehashOnLogin;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public static class Jwt {

        private String issuer = "fat-free-crm";

        /** HMAC-SHA256 key; must be supplied per environment (>= 32 bytes). */
        private String secret = "";

        private Duration accessTokenTtl = Duration.ofMinutes(30);

        private Duration refreshTokenTtl = Duration.ofDays(14);

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }
    }
}
