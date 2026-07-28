package com.giri.omsgateway.config;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

/**
 * Decorates any {@link ReactiveJwtDecoder} with a check against
 * oms-main's logout blacklist. Split out from {@link JwtDecoderConfig} as
 * its own class specifically so it can be unit tested against a stub
 * delegate/blacklist service, without needing a live JWKS endpoint or a
 * real Redis instance — {@code JwtDecoderConfig} is left as thin wiring
 * that just supplies the real {@code NimbusReactiveJwtDecoder} and the
 * real {@code TokenBlacklistService}.
 * <p>
 * Ordering is deliberate and load-bearing, see the tests: the delegate's
 * signature/expiry check runs first, so a malformed or expired token fails
 * fast without a Redis round trip, and the blacklist is never queried for
 * a token that was already going to be rejected.
 */
public class BlacklistCheckingJwtDecoder implements ReactiveJwtDecoder {

    private final ReactiveJwtDecoder delegate;
    private final TokenBlacklistService tokenBlacklistService;

    public BlacklistCheckingJwtDecoder(ReactiveJwtDecoder delegate, TokenBlacklistService tokenBlacklistService) {
        this.delegate = delegate;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    public Mono<Jwt> decode(String token) {
        return delegate.decode(token)
                .flatMap(jwt -> tokenBlacklistService.isBlacklisted(token)
                        .flatMap(blacklisted -> blacklisted
                                ? Mono.<Jwt>error(new BadJwtException("Token has been revoked"))
                                : Mono.just(jwt)));
    }
}
