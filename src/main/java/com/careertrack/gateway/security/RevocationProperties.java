package com.careertrack.gateway.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Where and how often to fetch Auth's revocation snapshot.
 *
 * @param url            AUTH_REVOCATIONS_URL (internal network)
 * @param internalApiKey REVOCATIONS_INTERNAL_API_KEY, same value as on
 *                       Auth; empty disables polling
 * @param pollInterval   how often to refresh (default 5 s)
 */
@Validated
@ConfigurationProperties(prefix = "careertrack.revocations")
public record RevocationProperties(
        @NotBlank @Pattern(regexp = "https?://.+") String url,
        String internalApiKey,
        @NotNull Duration pollInterval
) {
}
