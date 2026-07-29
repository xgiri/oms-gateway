package com.giri.omsgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

/**
 * Only exercises clientIpKeyResolver's own extraction/fallback logic —
 * whether server.forward-headers-strategy=framework correctly rewrites
 * getRemoteAddress() from X-Forwarded-For is Spring's own
 * ForwardedHeaderTransformer behavior, already covered by the framework's
 * own test suite, not re-tested here. What matters for this app is: given
 * whatever remoteAddress the request ends up with (real client IP once
 * forwarded headers are honored, direct TCP peer otherwise), does the
 * resolver extract it correctly and degrade sensibly when it's absent.
 */
class RateLimiterConfigTest {

    private final KeyResolver resolver = new RateLimiterConfig().clientIpKeyResolver();

    @Test
    void resolvesTheRequestsRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("203.0.113.42", 54321)));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("203.0.113.42")
                .verifyComplete();
    }

    @Test
    void fallsBackToUnknownWhenRemoteAddressIsAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("unknown")
                .verifyComplete();
    }
}
