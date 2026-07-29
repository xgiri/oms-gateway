package com.giri.omsgateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Turns a silent failure into a loud one. application-dev.properties
 * imports Vault as {@code optional:vault://} (see its comment for why) —
 * which means an unreachable Vault, a wrong VAULT_TOKEN, or
 * {@code secret/oms/<profile>} simply not having a {@code REDIS_PASSWORD}
 * key all fail the SAME way: the import silently no-ops,
 * spring.data.redis.password resolves to its empty default, and the app
 * starts up looking healthy. The actual failure only surfaces later, as a
 * Redis NOAUTH error on the first request that touches rate limiting or
 * the logout blacklist — far removed from VAULT_ENABLED=true being the
 * real cause, and easy to misdiagnose as a Redis problem instead of a
 * Vault one.
 * <p>
 * This only fires when Vault was actually supposed to supply the password
 * ({@code VAULT_ENABLED=true}) — a plain {@code REDIS_PASSWORD} env var
 * with Vault disabled is a legitimate path too (see this repo's README),
 * and a local Redis with no auth configured at all is legitimate whether
 * Vault is on or off, so this deliberately doesn't require a password to
 * be set in general — only that Vault, if it was switched on specifically
 * to supply one, actually did.
 */
@Component
public class VaultRedisPasswordGuard {

    private final Environment environment;

    public VaultRedisPasswordGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void verifyRedisPasswordResolvedWhenVaultEnabled() {
        boolean vaultEnabled = environment.getProperty("spring.cloud.vault.enabled", Boolean.class, false);
        if (!vaultEnabled) {
            return;
        }

        String redisPassword = environment.getProperty("spring.data.redis.password", "");
        if (redisPassword == null || redisPassword.isBlank()) {
            throw new IllegalStateException(
                    "VAULT_ENABLED=true but spring.data.redis.password resolved empty. Likely cause: "
                            + "application-dev.properties imports Vault as 'optional:vault://', so an "
                            + "unreachable Vault, a wrong VAULT_TOKEN, or secret/oms/<profile> not actually "
                            + "having a REDIS_PASSWORD key all fail SILENTLY rather than with an error here - "
                            + "the app would otherwise start looking healthy and only fail later, confusingly, "
                            + "as a Redis NOAUTH error. Refusing to start instead: confirm Vault is reachable "
                            + "at VAULT_ADDR, VAULT_TOKEN is correct, and secret/oms/<profile> has a "
                            + "REDIS_PASSWORD key (see vault/README.md).");
        }
    }
}
