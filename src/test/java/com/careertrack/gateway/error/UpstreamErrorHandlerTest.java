package com.careertrack.gateway.error;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamErrorHandlerTest {

    @Test
    void cannotConnectIs503() {
        assertThat(UpstreamErrorHandler.classify(new ConnectException("refused")))
                .contains(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(UpstreamErrorHandler.classify(new ConnectTimeoutException("connect timed out")))
                .contains(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(UpstreamErrorHandler.classify(new UnknownHostException("auth")))
                .contains(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(UpstreamErrorHandler.classify(new NoRouteToHostException()))
                .contains(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void noResponseInTimeIs504() {
        assertThat(UpstreamErrorHandler.classify(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout")))
                .contains(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(UpstreamErrorHandler.classify(new TimeoutException()))
                .contains(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(UpstreamErrorHandler.classify(ReadTimeoutException.INSTANCE))
                .contains(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void brokenConnectionIs502() {
        assertThat(UpstreamErrorHandler.classify(PrematureCloseException.TEST_EXCEPTION))
                .contains(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void looksThroughWrapperExceptions() {
        RuntimeException wrapped = new RuntimeException(
                "wrapper", new IllegalStateException("inner", new ConnectException("refused")));

        assertThat(UpstreamErrorHandler.classify(wrapped))
                .contains(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void leavesUnrelatedErrorsToTheDefaultHandler() {
        assertThat(UpstreamErrorHandler.classify(new IllegalArgumentException("bug"))).isEmpty();
        assertThat(UpstreamErrorHandler.classify(
                new ResponseStatusException(HttpStatus.NOT_FOUND))).isEmpty();
        assertThat(UpstreamErrorHandler.classify(
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS))).isEmpty();
    }
}
