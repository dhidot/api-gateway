package com.careertrack.gateway;

/*
@Author Didot
Created on 13/10/2026
@Last Modified on 13/10/2026 20:44
Version 1.0
*/

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RateLimitIntegrationTest {

    private static final DisposableServer authMock =
            HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .route(routes -> routes
                            .post("/api/auth/login",
                                    (request, response) ->
                                            response.status(200)
                                                    .sendString(Mono.just("ok"))
                            )
                            .post("/api/auth/register",
                                    (request, response) ->
                                            response.status(201)
                                                    .sendString(Mono.just("created"))
                            )
                    )
                    .bindNow();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL",
                () -> "http://127.0.0.1:" + authMock.port());

        registry.add("AUTH_LOGIN_RATE_CAPACITY", () -> "3");
        registry.add("AUTH_LOGIN_REFILL_TOKENS", () -> "3");
    }

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .build();
    }

    @AfterAll
    static void stopMock() {
        authMock.disposeNow();
    }

    @Test
    void shouldReturn429AfterLoginLimitExceeded() {
        WebTestClient client = client();

        for (int i = 0; i < 3; i++) {
            client.post()
                    .uri("/api/auth/login")
                    .exchange()
                    .expectStatus().isOk();
        }

        client.post()
                .uri("/api/auth/login")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                .exists("X-Correlation-ID");
    }

    @Test
    void shouldKeepRegisterPublic() {
        client().post()
                .uri("/api/auth/register")
                .exchange()
                .expectStatus().isCreated();
    }
}
