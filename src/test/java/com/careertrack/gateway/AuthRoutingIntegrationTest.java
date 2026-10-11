package com.careertrack.gateway;

/*
@Author Didot
Created on 11/10/2026
@Last Modified on 11/10/2026 13:53
Version 1.0
*/

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class AuthRoutingIntegrationTest {

    private static final DisposableServer authMock =
            HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .route(routes -> routes

                            .get("/api/auth/me", (request, response) ->
                                    response.status(401)
                                            .header("X-Test-Upstream", "auth-mock")
                                            .sendString(Mono.just("unauthorized"))
                            )

                            .post("/api/auth/login", (request, response) ->
                                    response.status(200)
                                            .header(
                                                    "Content-Type",
                                                    "application/json"
                                            )
                                            .sendString(
                                                    request.receive()
                                                            .aggregate()
                                                            .asString()
                                            )
                            )

                            .post("/api/auth/logout", (request, response) -> {
                                String authorization =
                                        request.requestHeaders()
                                                .get(HttpHeaders.AUTHORIZATION);

                                if ("Bearer stub-token".equals(authorization)) {
                                    return response.status(204).send();
                                }

                                return response.status(401).send();
                            })
                    )
                    .bindNow();

    @DynamicPropertySource
    static void configureGateway(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "AUTH_SERVICE_URL",
                () -> "http://127.0.0.1:" + authMock.port()
        );
    }

    @LocalServerPort
    private int gatewayPort;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .build();
    }

    @AfterAll
    static void tearDown() {
        authMock.disposeNow();
    }

    @Test
    void shouldForwardLoginRequestBody() {
        String body = "{\"probe\":true}";

        client.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo(body);
    }

    @Test
    void shouldPreserveUpstreamUnauthorizedResponse() {
        client.get()
                .uri("/api/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader()
                .valueEquals("X-Test-Upstream", "auth-mock")
                .expectBody(String.class)
                .isEqualTo("unauthorized");
    }

    @Test
    void shouldForwardAuthorizationHeader() {
        client.post()
                .uri("/api/auth/logout")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer stub-token"
                )
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldRejectUnknownRoute() {
        client.get()
                .uri("/api/unknown/test")
                .exchange()
                .expectStatus().isUnauthorized();
    }


    @Test
    void shouldAllowPublicGatewayHealth() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldRejectAdminRouteBeforeConfigured() {
        client.get()
                .uri("/api/admin/users")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldRejectCareerRouteBeforeConfigured() {
        client.get()
                .uri("/api/career/applications")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldNotExposeActuatorEnvironment() {
        client.get()
                .uri("/actuator/env")
                .exchange()
                .expectStatus().isUnauthorized();
    }


    @Test
    void shouldGenerateCorrelationId() {
        client.get()
                .uri("/api/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader()
                .valueMatches(
                        "X-Correlation-ID",
                        "^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$"
                );
    }

    @Test
    void shouldIncludeCorrelationIdOnGatewayRejection() {
        client.get()
                .uri("/api/admin/users")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader()
                .exists("X-Correlation-ID");
    }

}
