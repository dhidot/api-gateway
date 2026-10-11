package com.careertrack.gateway.filter;

/*
@Author Didot
Created on 11/10/2026
@Last Modified on 11/10/2026 18:01
Version 1.0
*/

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    private static final Logger log =
            LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            WebFilterChain chain
    ) {
        String id = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        var request = exchange.getRequest()
                .mutate()
                .headers(headers ->
                        headers.set("X-Correlation-ID", id))
                .build();

        var updated = exchange.mutate()
                .request(request)
                .build();

        updated.getResponse().beforeCommit(() -> {
            updated.getResponse().getHeaders()
                    .set("X-Correlation-ID", id);
            return Mono.empty();
        });

        return chain.filter(updated)
                .doFinally(signal -> log.info(
                        "requestId={} method={} status={} durationMs={}",
                        id,
                        request.getMethod(),
                        updated.getResponse().getStatusCode(),
                        System.currentTimeMillis() - start
                ));
    }
}


