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
    void shouldHandleAuthServiceUnavailable() {
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(15))
                .build()
                .get()
                .uri("/api/auth/me")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().exists("X-Correlation-ID");
    }

    private static int findUnusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
