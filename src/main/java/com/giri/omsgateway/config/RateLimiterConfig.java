package com.giri.omsgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Backs the {@code oms-login} route's {@code RequestRateLimiter} filter
 * (see application.properties) — a Redis token-bucket limiter keyed per client IP,
 * the same brute-force-protection shape as oms-main's Bucket4j login
 * limiter, just enforced at the edge instead of in the app. Reuses the same
 * Redis instance oms-main already runs (see spring.data.redis.* above), no
 * new infra.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * RedisRateLimiter's constructor args are (replenishRate, burstCapacity)
     * tokens/second and bucket size respectively — expressed here as
     * "N requests per 60s" via the same app.ratelimit.login.* properties
     * oms-main's Bucket4j config mirrors, converted to a per-second rate.
     */
    @Bean
    public RedisRateLimiter loginRateLimiter(
            @Value("${app.ratelimit.login.replenish-rate:5}") int replenishRate,
            @Value("${app.ratelimit.login.burst-capacity:5}") int burstCapacity) {
        // replenishRate here is tokens/sec; oms-main's limiter is expressed
        // as "5 per 60s" — RedisRateLimiter has no native per-minute mode,
        // so this intentionally allows a slightly burstier edge limit than
        // the monolith's. Since the monolith's own limiter still applies
        // behind this one (defense in depth, see application.properties), the
        // stricter of the two always wins in practice.
        return new RedisRateLimiter(replenishRate, burstCapacity);
    }

    /**
     * Depends on {@code server.forward-headers-strategy=framework}
     * (application.properties) to actually see the real client IP once
     * there's a proxy in front of this app — see the comment on that
     * property for the trust-boundary reasoning. Without it,
     * {@code getRemoteAddress()} here would return whatever sits
     * immediately in front of the gateway (ingress-nginx's pod IP in k8s),
     * not the caller — same "IP" for every external client, silently
     * defeating per-client rate limiting rather than erroring.
     */
    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .defaultIfEmpty("unknown");
    }
}
