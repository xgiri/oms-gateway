package com.giri.omsgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultRedisPasswordGuardTest {

    @Test
    void refusesToStartWhenVaultEnabledButRedisPasswordIsEmpty() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.cloud.vault.enabled", "true");
        environment.setProperty("spring.data.redis.password", "");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new VaultRedisPasswordGuard(environment).verifyRedisPasswordResolvedWhenVaultEnabled());
        assertTrue(ex.getMessage().contains("REDIS_PASSWORD"));
    }

    @Test
    void refusesToStartWhenVaultEnabledAndRedisPasswordIsUnset() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.cloud.vault.enabled", "true");
        // spring.data.redis.password never set at all — same as its ${REDIS_PASSWORD:} default resolving empty.

        assertThrows(IllegalStateException.class,
                () -> new VaultRedisPasswordGuard(environment).verifyRedisPasswordResolvedWhenVaultEnabled());
    }

    @Test
    void allowsStartupWhenVaultEnabledAndRedisPasswordIsPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.cloud.vault.enabled", "true");
        environment.setProperty("spring.data.redis.password", "s3cret");

        assertDoesNotThrow(
                () -> new VaultRedisPasswordGuard(environment).verifyRedisPasswordResolvedWhenVaultEnabled());
    }

    @Test
    void allowsEmptyRedisPasswordWhenVaultIsNotEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.cloud.vault.enabled", "false");
        environment.setProperty("spring.data.redis.password", "");

        assertDoesNotThrow(
                () -> new VaultRedisPasswordGuard(environment).verifyRedisPasswordResolvedWhenVaultEnabled());
    }
}
