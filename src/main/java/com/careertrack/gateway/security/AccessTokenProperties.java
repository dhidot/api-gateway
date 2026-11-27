package com.careertrack.gateway.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How access tokens for protected routes (/api/career/**) are verified
 * (ADR 0001). Same rules as career-service, which verifies again.
 *
 * @param jwkSetUri Auth's JWKS on the internal network (AUTH_JWKS_URI)
 */
@Validated
@ConfigurationProperties(prefix = "careertrack.access-token")
public record AccessTokenProperties(
        @NotBlank @Pattern(regexp = "https?://.+") String jwkSetUri,
        @NotBlank String issuer,
        @NotBlank String audience
) {
}
