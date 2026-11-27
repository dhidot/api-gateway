package com.careertrack.gateway.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Claim rules for access tokens on protected routes (ADR 0001), plus the
 * revocation check. Signature and algorithm (RS256 only) are checked by
 * the decoder first.
 */
public final class AccessTokenValidators {

    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final String INVALID_TOKEN = "invalid_token";

    private AccessTokenValidators() {
    }

    public static OAuth2TokenValidator<Jwt> of(AccessTokenProperties properties, RevocationList revocations) {
        return new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(properties.issuer()),
                audience(properties.audience()),
                accessTokenType(),
                uuidSubject(),
                notRevoked(revocations)
        ));
    }

    static OAuth2TokenValidator<Jwt> audience(String expected) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : failure("Unexpected audience");
    }

    static OAuth2TokenValidator<Jwt> accessTokenType() {
        return jwt -> "ACCESS".equals(jwt.getClaimAsString("type"))
                ? OAuth2TokenValidatorResult.success()
                : failure("Not an access token");
    }

    static OAuth2TokenValidator<Jwt> uuidSubject() {
        return jwt -> {
            String subject = jwt.getSubject();
            if (subject == null || subject.length() != 36) {
                return failure("Subject must be a user id");
            }
            try {
                UUID.fromString(subject);
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException e) {
                return failure("Subject must be a user id");
            }
        };
    }

    /** Logout (sid) and logout-all (iat before cut-off), see RevocationList. */
    static OAuth2TokenValidator<Jwt> notRevoked(RevocationList revocations) {
        return jwt -> revocations.isRevoked(jwt.getSubject(), jwt.getClaimAsString("sid"), jwt.getIssuedAt())
                ? failure("Token has been revoked")
                : OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(INVALID_TOKEN, description, null));
    }
}
