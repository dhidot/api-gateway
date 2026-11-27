package com.careertrack.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;

/**
 * Polls Auth's GET /internal/revocations and swaps the result into
 * {@link RevocationList} (ADR 0001, stage E).
 *
 * <ul>
 *   <li>Always the full active snapshot, never "changes since": a cursor
 *       over database sequences can skip rows committed out of order.</li>
 *   <li>If a poll fails the previous snapshot stays in use and a warning
 *       is logged; requests keep flowing. While Auth is down nobody can
 *       log out anyway, so nothing new is missed.</li>
 *   <li>Without REVOCATIONS_INTERNAL_API_KEY polling is disabled and a
 *       warning is logged at startup: tokens are still verified, but
 *       logout only takes effect when they expire.</li>
 * </ul>
 */
public class RevocationPoller implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RevocationPoller.class);

    static final String API_KEY_HEADER = "X-Internal-Api-Key";
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final RevocationList list;
    private final WebClient client;
    private final String url;
    private final String apiKey;
    private final Duration interval;
    private final Clock clock;

    private volatile Disposable subscription;
    private volatile boolean lastPollFailed;

    public RevocationPoller(
            RevocationList list,
            WebClient.Builder clientBuilder,
            RevocationProperties properties,
            Clock clock
    ) {
        this.list = list;
        this.client = clientBuilder.build();
        this.url = properties.url();
        this.apiKey = properties.internalApiKey() == null ? "" : properties.internalApiKey().trim();
        this.interval = properties.pollInterval();
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (apiKey.isEmpty()) {
            log.warn("REVOCATIONS_INTERNAL_API_KEY is not set: revocation polling is DISABLED. "
                    + "Logged-out tokens stay valid at the gateway until they expire.");
            return;
        }
        subscription = Flux.interval(Duration.ZERO, interval)
                .onBackpressureDrop()
                .concatMap(tick -> pollOnce(), 1)
                .subscribe();
    }

    /** One poll; never errors (failures keep the previous snapshot). */
    public Mono<Void> pollOnce() {
        return client.get()
                .uri(url)
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .bodyToMono(RevocationList.Payload.class)
                .timeout(REQUEST_TIMEOUT)
                .doOnNext(payload -> {
                    list.replace(payload, clock.instant());
                    if (lastPollFailed) {
                        log.info("revocation polling recovered; {} active entries", list.size());
                    }
                    lastPollFailed = false;
                })
                .onErrorResume(error -> {
                    if (!lastPollFailed) {
                        // Log the transition only, not every 5 s while Auth is down.
                        log.warn("revocation poll failed ({}); keeping last snapshot from {}",
                                error.getClass().getSimpleName(), list.loadedAt());
                    }
                    lastPollFailed = true;
                    return Mono.empty();
                })
                .then();
    }

    @Override
    public void destroy() {
        Disposable s = subscription;
        if (s != null) {
            s.dispose();
        }
    }
}
