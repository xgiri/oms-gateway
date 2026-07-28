package com.giri.omsgateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Read-only mirror of oms-main's {@code TokenBlacklistService} — the gateway
 * never writes a blacklist entry (that only ever happens on logout, handled
 * by oms-main's own /api/v1/auth/logout), it only checks one, using the
 * exact same Redis key scheme ({@code blacklist:jwt:<sha256-hex-of-token>})
 * so it's reading entries oms-main already wrote, on the same Redis instance
 * (see spring.data.redis.* in application.properties — same instance,
 * already shared for rate limiting).
 * <p>
 * Deliberately hashes the raw compact JWT string, not any claim inside it:
 * this has to match oms-main's key derivation bit-for-bit, and that's what
 * TokenBlacklistService.key(String token) hashes there too.
 */
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "blacklist:jwt:";

    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<Boolean> isBlacklisted(String token) {
        return redisTemplate.hasKey(key(token));
    }

    private String key(String token) {
        return KEY_PREFIX + sha256Hex(token);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a mandatory JDK algorithm — this can't actually happen.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
