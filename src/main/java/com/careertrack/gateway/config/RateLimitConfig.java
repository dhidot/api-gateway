package com.careertrack.gateway.config;

/*
@Author Didot
Created on 13/10/2026
@Last Modified on 13/10/2026 20:40
Version 1.0
*/

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    @Bean
    @SuppressWarnings("unchecked")
    AsyncProxyManager<String> caffeineProxyManager() {
        Caffeine<String, RemoteBucketState> cache =
                (Caffeine<String, RemoteBucketState>)
                        (Caffeine<?, ?>) Caffeine.newBuilder()
                                .maximumSize(100);

        return new CaffeineProxyManager<>(
                cache,
                Duration.ofMinutes(2)
        ).asAsync();
    }

    @Bean
    KeyResolver authPathKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getPath().value()
        );
    }
}

