package com.careertrack.gateway.error;

import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Turns failures to reach an upstream service into proper gateway status
 * codes with a small, consistent JSON body, instead of a generic 500:
 *
 * <ul>
 *   <li><b>503</b> — the upstream could not be reached (refused, DNS,
 *       no route, connect timeout). Adds {@code Retry-After}.</li>
 *   <li><b>504</b> — connected, but no response within the configured
 *       response timeout.</li>
 *   <li><b>502</b> — the connection broke or the response was unusable.</li>
 * </ul>
 *
 * Responses that the upstream itself returns (including its own 500/503)
 * are never touched: they are not exceptions. Exception messages are not
 * echoed to clients because they can contain internal host names and
 * ports.
 *
 * <p>Runs before Spring Boot's default handler ({@code @Order(-1)}).
 */
@Component
@Order(-2)
public class UpstreamErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UpstreamErrorHandler.class);

    static final String RETRY_AFTER_SECONDS = "5";

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        Optional<Classification> classified = classifyCause(ex);
        ServerHttpResponse response = exchange.getResponse();

        if (classified.isEmpty() || response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = classified.get().status();

        // Log the exception that decided the status, not the end of the
        // cause chain (Reactor Netty appends unrelated internal causes).
        log.warn(
                "upstream failure status={} cause={} method={} path={}",
                status.value(),
                classified.get().cause().getClass().getSimpleName(),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value()
        );

        response.setStatusCode(status);
        HttpHeaders headers = response.getHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setCacheControl("no-store");
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }

        String body = "{\"code\":\"" + status.value() + "\",\"message\":\""
                + messageFor(status) + "\"}";
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Maps an exception (or anything in its cause chain) to a gateway
     * status. Empty means "not an upstream failure; let the next handler
     * deal with it".
     */
    static Optional<HttpStatus> classify(Throwable ex) {
        return classifyCause(ex).map(Classification::status);
    }

    record Classification(HttpStatus status, Throwable cause) {
    }

    private static Optional<Classification> classifyCause(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ResponseStatusException rse
                    && rse.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
                return Optional.of(new Classification(HttpStatus.GATEWAY_TIMEOUT, t));
            }
            if (t instanceof TimeoutException || t instanceof ReadTimeoutException) {
                return Optional.of(new Classification(HttpStatus.GATEWAY_TIMEOUT, t));
            }
            // Never connected. Netty's ConnectTimeoutException (connect-timeout
            // exceeded) extends ConnectException, so it lands here too.
            if (t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || t instanceof UnknownHostException) {
                return Optional.of(new Classification(HttpStatus.SERVICE_UNAVAILABLE, t));
            }
            if (t instanceof PrematureCloseException) {
                return Optional.of(new Classification(HttpStatus.BAD_GATEWAY, t));
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }

    private static String messageFor(HttpStatus status) {
        return switch (status) {
            case SERVICE_UNAVAILABLE -> "Upstream service is unavailable. Please try again shortly.";
            case GATEWAY_TIMEOUT -> "Upstream service did not respond in time.";
            default -> "Upstream service returned an invalid response.";
        };
    }
}
