package com.careertrack.gateway.config;

/*
@Author Didot
Created on 13/10/2026
@Last Modified on 27/11/2026
Version 1.1
*/

import com.careertrack.gateway.filter.ClientAddressFilter;
import com.careertrack.gateway.ratelimit.ClientAddress;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /**
     * One bucket per (endpoint, client). The cache must hold far more
     * entries than concurrent clients: an evicted bucket starts full
     * again, so a small cache lets an attacker reset limits by cycling
     * through keys.
     */
    @Bean
    @SuppressWarnings("unchecked")
    AsyncProxyManager<String> caffeineProxyManager(
            @Value("${careertrack.gateway.rate-limit.max-buckets:50000}") long maxBuckets
    ) {
        Caffeine<String, RemoteBucketState> cache =
                (Caffeine<String, RemoteBucketState>)
                        (Caffeine<?, ?>) Caffeine.newBuilder()
                                .maximumSize(maxBuckets);

        return new CaffeineProxyManager<>(
                cache,
                Duration.ofMinutes(2)
        ).asAsync();
    }

    /**
     * Key = request path + client, resolved by {@link ClientAddressFilter}.
     * Previously the key was the path alone, so every user shared one
     * bucket and a single client could lock everyone out of login.
     */
    @Bean
    KeyResolver authClientKeyResolver() {
        return exchange -> {
            Object client = exchange.getAttributes()
                    .getOrDefault(
                            ClientAddressFilter.CLIENT_KEY_ATTRIBUTE,
                            ClientAddress.UNKNOWN
                    );

            return Mono.just(
                    exchange.getRequest().getPath().value() + "|" + client
            );
        };
    }
}
