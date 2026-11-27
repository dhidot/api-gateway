package com.careertrack.gateway;

import com.careertrack.gateway.support.TestAuthServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0001, stage E: /api/career/** is protected by the gateway (token +
 * revocation list); /api/auth/** keeps passing through untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CareerRouteIntegrationTest {

    private static final TestAuthServer auth = new TestAuthServer();
    private static final AtomicInteger careerHits = new AtomicInteger();

    private static final DisposableServer careerMock = HttpServer.create()
            .host("127.0.0.1").port(0)
            .route(routes -> routes.get("/api/career/applications", (req, res) -> {
                careerHits.incrementAndGet();
                String authz = req.requestHeaders().get("Authorization");
                return res.status(200)
                        .header("X-Seen-Authorization", authz != null && authz.startsWith("Bearer ") ? "yes" : "no")
                        .sendString(Mono.just("{\"code\":\"200\"}")).then();
            }))
            .bindNow();

    private static final DisposableServer authMock = HttpServer.create()
            .host("127.0.0.1").port(0)
            .route(routes -> routes.get("/api/auth/me", (req, res) ->
                    res.status(200).sendString(Mono.just("auth-decides")).then()))
            .bindNow();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("CAREER_SERVICE_URL", () -> "http://127.0.0.1:" + careerMock.port());
        registry.add("AUTH_SERVICE_URL", () -> "http://127.0.0.1:" + authMock.port());
        registry.add("AUTH_JWKS_URI", () -> auth.baseUrl() + "/.well-known/jwks.json");
        registry.add("AUTH_REVOCATIONS_URL", () -> auth.baseUrl() + "/internal/revocations");
        registry.add("REVOCATIONS_INTERNAL_API_KEY", () -> TestAuthServer.REVOCATIONS_KEY);
        registry.add("REVOCATIONS_POLL_INTERVAL", () -> "200ms");
    }

    @AfterAll
    static void stop() {
        careerMock.disposeNow();
        authMock.disposeNow();
        auth.stop();
    }

    @LocalServerPort
    private int port;

    private final UUID alice = UUID.randomUUID();

    @BeforeEach
    void reset() {
        auth.clearRevocations();
        waitForNextPoll();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private WebTestClient.ResponseSpec career(String token) {
        var spec = client().get().uri("/api/career/applications");
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.exchange();
    }

    /** Waits until the gateway has polled the (possibly changed) snapshot. */
    private static void waitForNextPoll() {
        int before = auth.revocationHits();
        waitUntil(() -> auth.revocationHits() >= before + 2, Duration.ofSeconds(5));
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    // =================================================================
    // Valid tokens are forwarded
    // =================================================================

    @Test
    void validTokenIsForwardedWithTheAuthorizationHeader() {
        career(auth.accessToken(alice))
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Seen-Authorization", "yes")
                .expectHeader().exists("X-Correlation-ID");
    }

    // =================================================================
    // Invalid tokens never reach career-service
    // =================================================================

    @Test
    void invalidTokensAreRejectedAtTheGateway() {
        int before = careerHits.get();

        career(null).expectStatus().isUnauthorized();
        career(auth.accessToken(alice, c -> c.expirationTime(Date.from(Instant.now().minusSeconds(120)))))
                .expectStatus().isUnauthorized();
        career(auth.accessToken(alice, c -> c.issuer("someone-else"))).expectStatus().isUnauthorized();
        career(auth.accessToken(alice, c -> c.audience("another-api"))).expectStatus().isUnauthorized();
        career(auth.accessToken(alice, c -> c.claim("type", "REFRESH"))).expectStatus().isUnauthorized();
        career(auth.accessToken(alice, c -> c.subject("dhito"))).expectStatus().isUnauthorized();
        career(auth.withUnknownKid(alice)).expectStatus().isUnauthorized();
        career(auth.withAttackerKey(alice)).expectStatus().isUnauthorized();
        career(auth.hs256(alice)).expectStatus().isUnauthorized();
        career(auth.unsigned(alice)).expectStatus().isUnauthorized();
        career("not-a-jwt").expectStatus().isUnauthorized();

        assertThat(careerHits.get()).isEqualTo(before);
    }

    // =================================================================
    // Revocation list (logout / logout-all)
    // =================================================================

    @Test
    void loggedOutSessionIsRejectedWithinAPoll() {
        String sid = UUID.randomUUID().toString();
        String laptop = auth.accessToken(alice, c -> c.claim("sid", sid));
        String phone = auth.accessToken(alice);

        career(laptop).expectStatus().isOk();

        auth.revokeSession(alice, sid);
        waitForNextPoll();

        career(laptop).expectStatus().isUnauthorized();
        career(phone).expectStatus().isOk();
    }

    @Test
    void logoutEverywhereRejectsTokensIssuedBeforeTheCutOff() {
        long cutOff = Instant.now().getEpochSecond() + 1;
        String before = auth.accessToken(alice, c -> c.issueTime(Date.from(Instant.ofEpochSecond(cutOff - 1))));
        String after = auth.accessToken(alice, c -> c.issueTime(Date.from(Instant.ofEpochSecond(cutOff))));
        String otherUser = auth.accessToken(UUID.randomUUID(),
                c -> c.issueTime(Date.from(Instant.ofEpochSecond(cutOff - 1))));

        auth.revokeUser(alice, cutOff);
        waitForNextPoll();

        career(before).expectStatus().isUnauthorized();
        career(after).expectStatus().isOk();
        career(otherUser).expectStatus().isOk();
    }

    @Test
    void keepsTheLastSnapshotWhenAuthIsUnreachable() {
        String sid = UUID.randomUUID().toString();
        String token = auth.accessToken(alice, c -> c.claim("sid", sid));
        auth.revokeSession(alice, sid);
        waitForNextPoll();

        auth.failRevocations();
        waitForNextPoll();

        career(token).expectStatus().isUnauthorized();
        career(auth.accessToken(alice)).expectStatus().isOk();
    }

    @Test
    void pollsWithTheInternalKey() {
        assertThat(auth.revocationHits()).isPositive();
        assertThat(auth.unauthorizedHits()).isZero();
    }

    // =================================================================
    // Auth routes are not affected (transition: legacy HMAC tokens)
    // =================================================================

    @Test
    void authRoutesStillPassThroughWithAnyBearerToken() {
        client().get().uri("/api/auth/me")
                .header("Authorization", "Bearer " + auth.hs256(alice))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("auth-decides");
    }

    @Test
    void otherPathsStayDenied() {
        client().get().uri("/api/unknown")
                .header("Authorization", "Bearer " + auth.accessToken(alice))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
