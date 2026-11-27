package com.careertrack.gateway;

/*
@Author Didot
Created on 11/10/2026
@Last Modified on 11/10/2026 18:09
Version 1.0
*/

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class AuthUnavailableIntegrationTest {

    private static final int unavailablePort = findUnusedPort();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add(
                "AUTH_SERVICE_URL",
                () -> "http://127.0.0.1:" + unavailablePort
        );
    }

    @Test
    void shouldReturn503WhenAuthServiceCannotBeReached() {
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(15))
                .build()
                .get()
                .uri("/api/auth/me")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().exists("X-Correlation-ID")
                .expectHeader().valueEquals("Retry-After", "5")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().contentType("application/json")
                .expectBody()
                .jsonPath("$.code").isEqualTo("503")
                .jsonPath("$.message").isEqualTo(
                        "Upstream service is unavailable. Please try again shortly.")
                // Internal address must not leak to clients.
                .consumeWith(result -> {
                    String body = new String(result.getResponseBodyContent());
                    if (body.contains("127.0.0.1") || body.contains(String.valueOf(unavailablePort))) {
                        throw new AssertionError("body leaks upstream address: " + body);
                    }
                });
    }

    @Test
    void rateLimitedRoutesAlsoReturn503() {
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(15))
                .build()
                .post()
                .uri("/api/auth/login")
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    private static int findUnusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
