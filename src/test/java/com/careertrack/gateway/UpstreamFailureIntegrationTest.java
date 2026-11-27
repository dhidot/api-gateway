package com.careertrack.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;

/**
 * Gateway status codes when the upstream is reachable but misbehaves,
 * and proof that the upstream's own error responses are passed through.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class UpstreamFailureIntegrationTest {

    private static final DisposableServer authMock =
            HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .route(routes -> routes
                            // Slower than the test response-timeout (500 ms).
                            .get("/api/auth/slow", (request, response) ->
                                    Mono.delay(Duration.ofSeconds(3))
                                            .then(response.sendString(Mono.just("late")).then()))
                            // Accepts the request, then drops the connection.
                            .get("/api/auth/broken", (request, response) ->
                                    response.withConnection(connection -> connection.dispose())
                                            .then())
                            // Upstream's own errors must reach the client unchanged.
                            .get("/api/auth/boom", (request, response) ->
                                    response.status(500)
                                            .header("Content-Type", "application/json")
                                            .sendString(Mono.just("{\"code\":\"500\",\"message\":\"auth bug\"}"))
                                            .then())
                            .get("/api/auth/maintenance", (request, response) ->
                                    response.status(503)
                                            .header("Content-Type", "application/json")
                                            .sendString(Mono.just("{\"code\":\"503\",\"message\":\"auth maintenance\"}"))
                                            .then())
                    )
                    .bindNow();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + authMock.port());
        registry.add("spring.cloud.gateway.server.webflux.httpclient.response-timeout", () -> "500ms");
    }

    @LocalServerPort
    private int gatewayPort;

    @AfterAll
    static void stopMock() {
        authMock.disposeNow();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void slowUpstreamIs504() {
        client().get().uri("/api/auth/slow")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectHeader().exists("X-Correlation-ID")
                .expectHeader().doesNotExist("Retry-After")
                .expectBody()
                .jsonPath("$.code").isEqualTo("504")
                .jsonPath("$.message").isEqualTo("Upstream service did not respond in time.");
    }

    @Test
    void droppedConnectionIs502() {
        client().get().uri("/api/auth/broken")
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().exists("X-Correlation-ID")
                .expectBody()
                .jsonPath("$.code").isEqualTo("502")
                .jsonPath("$.message").isEqualTo("Upstream service returned an invalid response.");
    }

    @Test
    void upstream500IsPassedThroughUnchanged() {
        client().get().uri("/api/auth/boom")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.message").isEqualTo("auth bug");
    }

    @Test
    void upstream503IsPassedThroughUnchanged() {
        client().get().uri("/api/auth/maintenance")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().doesNotExist("Retry-After")
                .expectBody()
                .jsonPath("$.message").isEqualTo("auth maintenance");
    }
}
