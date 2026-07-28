package com.giri.omsgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link BlacklistCheckingJwtDecoder} directly against stub
 * collaborators — no live JWKS endpoint, no real Redis. What's under test
 * is the decision logic (allow / reject / fail-fast ordering), which is
 * exactly what a real JWKS+Redis integration test would obscure behind
 * network setup.
 */
class BlacklistCheckingJwtDecoderTest {

    private static final String TOKEN = "some.jwt.token";

    private final ReactiveJwtDecoder delegate = mock(ReactiveJwtDecoder.class);
    private final TokenBlacklistService blacklistService = mock(TokenBlacklistService.class);
    private final BlacklistCheckingJwtDecoder decoder =
            new BlacklistCheckingJwtDecoder(delegate, blacklistService);

    @Test
    void allowsATokenThatIsValidAndNotBlacklisted() {
        Jwt jwt = validJwt();
        when(delegate.decode(TOKEN)).thenReturn(Mono.just(jwt));
        when(blacklistService.isBlacklisted(TOKEN)).thenReturn(Mono.just(false));

        StepVerifier.create(decoder.decode(TOKEN))
                .expectNext(jwt)
                .verifyComplete();
    }

    @Test
    void rejectsATokenThatIsValidButBlacklisted() {
        when(delegate.decode(TOKEN)).thenReturn(Mono.just(validJwt()));
        when(blacklistService.isBlacklisted(TOKEN)).thenReturn(Mono.just(true));

        StepVerifier.create(decoder.decode(TOKEN))
                .expectErrorMatches(ex -> ex instanceof BadJwtException
                        && ex.getMessage().contains("revoked"))
                .verify();
    }

    @Test
    void skipsTheBlacklistLookupWhenSignatureOrExpiryAlreadyFailed() {
        when(delegate.decode(TOKEN)).thenReturn(Mono.error(new BadJwtException("bad signature")));

        StepVerifier.create(decoder.decode(TOKEN))
                .expectErrorMatches(ex -> ex instanceof BadJwtException
                        && ex.getMessage().contains("bad signature"))
                .verify();

        // The whole point of checking signature/expiry first: a malformed or
        // expired token never triggers the extra Redis round trip.
        verifyNoInteractions(blacklistService);
    }

    private static Jwt validJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .claim("sub", "test-user")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }
}
