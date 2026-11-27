package com.careertrack.gateway;

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
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Login/register limits are per client, not shared by all users.
 * Each test uses its own client addresses so buckets do not overlap.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class PerClientRateLimitIntegrationTest {

    private static final String BFF_KEY = "test-bff-key-0123456789abcdefghijkl";
    private static final String CLIENT_IP = "X-CareerTrack-Client-IP";
    private static final String BFF_HEADER = "X-CareerTrack-BFF-Key";

    private static final DisposableServer authMock =
            HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .route(routes -> routes
                            .post("/api/auth/login", PerClientRateLimitIntegrationTest::echo)
                            .post("/api/auth/register", PerClientRateLimitIntegrationTest::echo)
                            .post("/api/auth/refresh", PerClientRateLimitIntegrationTest::echo)
                    )
                    .bindNow();

    /** Reports back which trusted headers reached the upstream. */
    private static Mono<Void> echo(HttpServerRequest request, HttpServerResponse response) {
        var headers = request.requestHeaders();
        return response.status(200)
                .header("X-Seen-Client-IP", headers.contains(CLIENT_IP) ? "present" : "absent")
                .header("X-Seen-BFF-Key", headers.contains(BFF_HEADER) ? "present" : "absent")
                .sendString(Mono.just("ok"))
                .then();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + authMock.port());
        registry.add("GATEWAY_BFF_KEY", () -> BFF_KEY);
        registry.add("AUTH_LOGIN_RATE_CAPACITY", () -> "2");
        registry.add("AUTH_LOGIN_REFILL_TOKENS", () -> "2");
        registry.add("AUTH_REGISTER_RATE_CAPACITY", () -> "2");
        registry.add("AUTH_REGISTER_REFILL_TOKENS", () -> "2");
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
                .build();
    }

    private WebTestClient.ResponseSpec post(String path, String clientIp, String key) {
        var spec = client().post().uri(path);
        if (clientIp != null) {
            spec = spec.header(CLIENT_IP, clientIp);
        }
        if (key != null) {
            spec = spec.header(BFF_HEADER, key);
        }
        return spec.exchange();
    }

    @Test
    void oneClientHittingTheLimitDoesNotBlockAnother() {
        post("/api/auth/login", "198.51.100.1", BFF_KEY).expectStatus().isOk();
        post("/api/auth/login", "198.51.100.1", BFF_KEY).expectStatus().isOk();
        post("/api/auth/login", "198.51.100.1", BFF_KEY)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        post("/api/auth/login", "198.51.100.2", BFF_KEY).expectStatus().isOk();
    }

    @Test
    void forgedClientIpWithoutTheKeyIsIgnored() {
        // Wrong key: every request falls back to the socket address
        // (127.0.0.1), so rotating the forged IP does not help.
        post("/api/auth/login", "203.0.113.1", "wrong-key").expectStatus().isOk();
        post("/api/auth/login", "203.0.113.2", null).expectStatus().isOk();
        post("/api/auth/login", "203.0.113.3", "wrong-key")
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void invalidClientIpWithAValidKeyFallsBackToTheSocketAddress() {
        post("/api/auth/register", "not-an-ip", BFF_KEY).expectStatus().isOk();
        post("/api/auth/register", "evil.example", BFF_KEY).expectStatus().isOk();
        post("/api/auth/register", "999.1.1.1", BFF_KEY)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void ipv6ClientsShareABucketPerPrefix64() {
        post("/api/auth/login", "2001:db8:aa:1::1", BFF_KEY).expectStatus().isOk();
        post("/api/auth/login", "2001:db8:aa:1::beef", BFF_KEY).expectStatus().isOk();
        post("/api/auth/login", "2001:db8:aa:1:ffff::9", BFF_KEY)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        post("/api/auth/login", "2001:db8:aa:2::1", BFF_KEY).expectStatus().isOk();
    }

    @Test
    void onlyTheLastForwardedForHopIsTrusted() {
        // Caddy appends the real peer last; anything before it is
        // client-controlled and must not create new buckets.
        client().post().uri("/api/auth/login")
                .header("X-Forwarded-For", "192.0.2.10, 192.0.2.77")
                .exchange().expectStatus().isOk();
        client().post().uri("/api/auth/login")
                .header("X-Forwarded-For", "192.0.2.11, 192.0.2.77")
                .exchange().expectStatus().isOk();
        client().post().uri("/api/auth/login")
                .header("X-Forwarded-For", "192.0.2.12, 192.0.2.77")
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        client().post().uri("/api/auth/login")
                .header("X-Forwarded-For", "192.0.2.10, 192.0.2.78")
                .exchange().expectStatus().isOk();
    }

    @Test
    void trustedHeadersAreNeverForwardedUpstream() {
        post("/api/auth/refresh", "198.51.100.50", BFF_KEY)
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Client-IP", "absent")
                .expectHeader().valueEquals("X-Seen-BFF-Key", "absent");

        post("/api/auth/refresh", "198.51.100.50", "forged")
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Client-IP", "absent")
                .expectHeader().valueEquals("X-Seen-BFF-Key", "absent");
    }
}
