package com.careertrack.gateway.filter;

import com.careertrack.gateway.ratelimit.ClientAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Works out which client a request belongs to, for per-client rate
 * limiting, and stores it as an exchange attribute.
 *
 * <p>All browser traffic reaches the gateway through the Next.js BFF on
 * Vercel, so the TCP peer (and Caddy's X-Forwarded-For) is a Vercel IP
 * shared by every user. The BFF therefore forwards the real client IP
 * in {@value #CLIENT_IP_HEADER}, authenticated by a shared secret in
 * {@value #BFF_KEY_HEADER}. Without a valid key that header is ignored
 * and the last X-Forwarded-For hop (written by Caddy) or the socket
 * address is used instead.
 *
 * <p>Both headers are removed before the request is forwarded, so the
 * secret never reaches upstream services and a client cannot pass a
 * forged IP through to them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ClientAddressFilter implements WebFilter {

    public static final String CLIENT_KEY_ATTRIBUTE =
            ClientAddressFilter.class.getName() + ".clientKey";

    public static final String CLIENT_IP_HEADER = "X-CareerTrack-Client-IP";
    public static final String BFF_KEY_HEADER = "X-CareerTrack-BFF-Key";

    static final int MIN_KEY_LENGTH = 32;

    // Only the hop Caddy appends is trusted; anything a client put in
    // front of it is ignored.
    private static final RemoteAddressResolver FORWARDED_RESOLVER =
            XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    private final byte[] bffKey;

    public ClientAddressFilter(
            @Value("${careertrack.gateway.bff-key:}") String bffKey
    ) {
        String key = bffKey == null ? "" : bffKey.trim();

        if (!key.isEmpty() && key.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "careertrack.gateway.bff-key (GATEWAY_BFF_KEY) must be at least "
                            + MIN_KEY_LENGTH + " characters, or empty to disable"
            );
        }

        this.bffKey = key.isEmpty() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String clientKey = resolveClientKey(exchange);

        var request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.remove(CLIENT_IP_HEADER);
                    headers.remove(BFF_KEY_HEADER);
                })
                .build();

        var updated = exchange.mutate().request(request).build();
        updated.getAttributes().put(CLIENT_KEY_ATTRIBUTE, clientKey);

        return chain.filter(updated);
    }

    private String resolveClientKey(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders();

        if (bffKey != null && isTrustedBff(headers.getFirst(BFF_KEY_HEADER))) {
            var forwarded = ClientAddress.toKey(headers.getFirst(CLIENT_IP_HEADER));
            if (forwarded.isPresent()) {
                return forwarded.get();
            }
        }

        InetSocketAddress remote = FORWARDED_RESOLVER.resolve(exchange);

        if (remote == null) {
            return ClientAddress.UNKNOWN;
        }

        // A forwarded hop comes back unresolved (host string only).
        String host = remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : remote.getHostString();

        return ClientAddress.toKey(host).orElse(ClientAddress.UNKNOWN);
    }

    private boolean isTrustedBff(String presented) {
        if (presented == null) {
            return false;
        }
        // Constant-time comparison: response timing must not reveal
        // how much of the key matched.
        return MessageDigest.isEqual(
                bffKey,
                presented.getBytes(StandardCharsets.UTF_8)
        );
    }
}
