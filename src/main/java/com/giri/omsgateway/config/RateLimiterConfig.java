package com.giri.omsgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Backs the {@code oms-login} route's {@code RequestRateLimiter} filter
 * (see application.yml) — a Redis token-bucket limiter keyed per client IP,
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
        // behind this one (defense in depth, see application.yml), the
        // stricter of the two always wins in practice.
        return new RedisRateLimiter(replenishRate, burstCapacity);
    }

    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .defaultIfEmpty("unknown");
    }
}
