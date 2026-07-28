package com.giri.omsgateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Closes the revocation gap flagged in the README: the auto-configured
 * decoder that {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
 * would otherwise produce only checks signature and expiry. Declaring a
 * ReactiveJwtDecoder bean explicitly here makes Spring Boot's
 * ReactiveOAuth2ResourceServerAutoConfiguration back off from creating its
 * own — SecurityConfig's {@code .oauth2ResourceServer(oauth2 ->
 * oauth2.jwt(jwt -> {}))} picks this bean up unchanged, so no other wiring
 * moves.
 * <p>
 * The actual decorating logic lives in {@link BlacklistCheckingJwtDecoder}
 * (kept separate so it's unit-testable against a stub delegate, rather than
 * needing a live JWKS endpoint) — this class only supplies the two real
 * collaborators: the JWKS-backed Nimbus decoder, and the real
 * TokenBlacklistService.
 */
@Configuration
@RequiredArgsConstructor
public class JwtDecoderConfig {

    private final TokenBlacklistService tokenBlacklistService;

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {

        NimbusReactiveJwtDecoder delegate = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        return new BlacklistCheckingJwtDecoder(delegate, tokenBlacklistService);
    }
}
