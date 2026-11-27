package com.careertrack.gateway.security;

/*
@Author Didot
Created on 11/10/2026
@Last Modified on 27/11/2026
Version 2.0
*/

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/**
 * Two separate filter chains:
 *
 * <ol>
 *   <li><b>/api/career/**</b>: the gateway verifies the access token itself
 *       (RS256 via Auth's JWKS, iss/aud/type/sub, revocation list) before
 *       forwarding. ADR 0001, stage E.</li>
 *   <li><b>Everything else</b>: unchanged. /api/auth/** is passed through
 *       and Auth Service decides. Bearer tokens are deliberately NOT
 *       inspected here: legacy HMAC tokens (valid on Auth until the
 *       transition ends) must keep reaching Auth for /me, refresh and
 *       logout.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties({AccessTokenProperties.class, RevocationProperties.class})
public class SecurityConfig {

    // ------------------------------------------------------------------
    // Chain 1: protected API routes
    // ------------------------------------------------------------------

    @Bean
    @Order(1)
    SecurityWebFilterChain protectedApiChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder accessTokenDecoder
    ) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/career/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchange -> exchange.anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(accessTokenDecoder)))
                .build();
    }

    /**
     * RS256 only (no alg:none, no HMAC). Keys come from Auth's JWKS and are
     * refreshed when an unknown kid appears.
     */
    @Bean
    ReactiveJwtDecoder accessTokenDecoder(AccessTokenProperties properties, RevocationList revocations) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(AccessTokenValidators.of(properties, revocations));
        return decoder;
    }

    @Bean
    RevocationList revocationList() {
        return new RevocationList();
    }

    @Bean
    RevocationPoller revocationPoller(
            RevocationList list,
            RevocationProperties properties,
            Clock clock
    ) {
        // Spring Boot 4 no longer auto-configures WebClient.Builder here;
        // WebFlux is on the classpath, so build a plain client.
        return new RevocationPoller(list, WebClient.builder(), properties, clock);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // ------------------------------------------------------------------
    // Chain 2: everything else (unchanged behaviour)
    // ------------------------------------------------------------------

    @Bean
    @Order(2)
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http
    ) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)

                .securityContextRepository(
                        NoOpServerSecurityContextRepository.getInstance()
                )

                .authorizeExchange(exchange -> exchange

                        // Gateway health check
                        .pathMatchers(
                                HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        ).permitAll()

                        // Auth Service owns endpoint authentication
                        .pathMatchers("/api/auth/**")
                        .permitAll()

                        // Default deny
                        .anyExchange()
                        .denyAll()
                )

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                new HttpStatusServerEntryPoint(
                                        HttpStatus.UNAUTHORIZED
                                )
                        )
                        .accessDeniedHandler(
                                new HttpStatusServerAccessDeniedHandler(
                                        HttpStatus.FORBIDDEN
                                )
                        )
                )

                .build();
    }
}
