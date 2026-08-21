package com.fatfreecrm.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Issues and parses the access/refresh tokens returned by {@code /api/v1/auth/login}. */
@Service
public final class JwtService {

    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";
    static final String CLAIM_TOKEN_TYPE = "typ";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecurityProperties properties;
    private final SecretKey key;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "ffcrm.security.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
        this.key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret);
    }

    public String issueAccessToken(CrmUserDetails user) {
        return issue(user, TOKEN_TYPE_ACCESS, properties.getJwt().getAccessTokenTtl());
    }

    public String issueRefreshToken(CrmUserDetails user) {
        return issue(user, TOKEN_TYPE_REFRESH, properties.getJwt().getRefreshTokenTtl());
    }

    public Duration accessTokenTtl() {
        return properties.getJwt().getAccessTokenTtl();
    }

    /** Returns the token's claims, or throws {@link JwtException} if it is invalid or expired. */
    public Claims parse(String token, String expectedType) {
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .requireIssuer(properties.getJwt().getIssuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();
        if (!expectedType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new JwtException("Expected a " + expectedType + " token");
        }
        return claims;
    }

    private String issue(CrmUserDetails user, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
            .issuer(properties.getJwt().getIssuer())
            .subject(user.getUsername())
            .claim(CLAIM_TOKEN_TYPE, type)
            .claim("uid", user.getId())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact();
    }
}
