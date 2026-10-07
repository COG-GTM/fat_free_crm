package com.fatfreecrm.security;

import com.fatfreecrm.domain.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private final JwtProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;

    public JwtTokenService(JwtProperties properties, SecretKey jwtSecretKey, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtClaimValidator<String>("typ", "refresh"::equals)
        ));
        refreshDecoder = decoder;
    }

    public TokenPair issue(User user) {
        return new TokenPair(
            issue(user, "access", properties.accessTokenTtl()),
            issue(user, "refresh", properties.refreshTokenTtl()),
            properties.accessTokenTtl().toSeconds()
        );
    }

    public Jwt verifyRefresh(String token) throws JwtException {
        return refreshDecoder.decode(token);
    }

    private String issue(User user, String type, java.time.Duration ttl) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject(user.getId().toString())
            .claim("username", user.getUsername())
            .claim("admin", user.isAdmin())
            .claim("typ", type)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .id(UUID.randomUUID().toString())
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public record TokenPair(String accessToken, String refreshToken, long expiresIn) {
    }
}
