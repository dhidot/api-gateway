package com.careertrack.gateway.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Stands in for Auth Service: serves a real JWKS and a mutable
 * /internal/revocations snapshot over HTTP, and issues tokens exactly like
 * Auth does (ADR 0001).
 */
public final class TestAuthServer {

    public static final String REVOCATIONS_KEY = "revocations-key-for-gateway-tests-0123456789";

    private static final String EMPTY_SNAPSHOT = "{\"serverTime\":0,\"revocations\":[]}";

    private final RSAKey key;
    private final RSAKey attackerKeySameKid;
    private final HttpServer server;

    private final AtomicReference<String> snapshot = new AtomicReference<>(EMPTY_SNAPSHOT);
    private final AtomicInteger snapshotStatus = new AtomicInteger(200);
    private final AtomicInteger revocationHits = new AtomicInteger();
    private final AtomicInteger unauthorizedHits = new AtomicInteger();

    public TestAuthServer() {
        try {
            key = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).keyIDFromThumbprint(true).generate();
            attackerKeySameKid = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();

            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", ex -> respond(ex, 200, jwks));
            server.createContext("/internal/revocations", ex -> {
                if (!REVOCATIONS_KEY.equals(ex.getRequestHeaders().getFirst("X-Internal-Api-Key"))) {
                    unauthorizedHits.incrementAndGet();
                    respond(ex, 401, new byte[0]);
                    return;
                }
                revocationHits.incrementAndGet();
                int status = snapshotStatus.get();
                respond(ex, status, status == 200
                        ? snapshot.get().getBytes(StandardCharsets.UTF_8)
                        : "boom".getBytes(StandardCharsets.UTF_8));
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------
    // Revocation snapshot control
    // ------------------------------------------------------------------

    public void clearRevocations() {
        snapshot.set(EMPTY_SNAPSHOT);
        snapshotStatus.set(200);
    }

    public void revokeSession(UUID user, String sid) {
        snapshot.set("{\"serverTime\":0,\"revocations\":[{\"type\":\"SESSION\",\"sub\":\"" + user
                + "\",\"sid\":\"" + sid + "\",\"expiresAt\":9999999999}]}");
    }

    public void revokeUser(UUID user, long notBeforeEpochSeconds) {
        snapshot.set("{\"serverTime\":0,\"revocations\":[{\"type\":\"USER\",\"sub\":\"" + user
                + "\",\"notBefore\":" + notBeforeEpochSeconds + ",\"expiresAt\":9999999999}]}");
    }

    public void failRevocations() {
        snapshotStatus.set(500);
    }

    public int revocationHits() {
        return revocationHits.get();
    }

    public int unauthorizedHits() {
        return unauthorizedHits.get();
    }

    // ------------------------------------------------------------------
    // Tokens
    // ------------------------------------------------------------------

    public static JWTClaimsSet.Builder accessClaims(UUID userId) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .jwtID(UUID.randomUUID().toString())
                .issuer("careertrack-auth")
                .audience("careertrack-api")
                .subject(userId.toString())
                .claim("sid", UUID.randomUUID().toString())
                .claim("username", "user")
                .claim("type", "ACCESS")
                .claim("tokenVersion", 0)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)));
    }

    public String accessToken(UUID userId) {
        return sign(accessClaims(userId).build(), key, key.getKeyID());
    }

    public String accessToken(UUID userId, Consumer<JWTClaimsSet.Builder> customize) {
        JWTClaimsSet.Builder claims = accessClaims(userId);
        customize.accept(claims);
        return sign(claims.build(), key, key.getKeyID());
    }

    public String withUnknownKid(UUID userId) {
        return sign(accessClaims(userId).build(), key, "unknown-kid");
    }

    public String withAttackerKey(UUID userId) {
        return sign(accessClaims(userId).build(), attackerKeySameKid, key.getKeyID());
    }

    public String hs256(UUID userId) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), accessClaims(userId).build());
            jwt.sign(new MACSigner("a-32-byte-secret-for-hs256-tests!".getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String unsigned(UUID userId) {
        return new PlainJWT(accessClaims(userId).build()).serialize();
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey, String kid) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(kid).type(JOSEObjectType.JWT).build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void stop() {
        server.stop(0);
    }
}
