package com.giri.omsgateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Defense in depth alongside the network-level lock already in place: the
 * "metrics" port (management.server.port, see application.properties)
 * carries every actuator endpoint including a would-be-enabled
 * /actuator/gateway/**, and it's never part of
 * k8s/03-service-gateway.yaml's port list or referenced by
 * k8s/04-ingress.yaml — so it's already unreachable from outside the
 * cluster regardless of what's exposed on it, same reasoning as
 * oms-main's own metrics port.
 * <p>
 * That protects against the network path, not the config path: nothing
 * stops the debug override commented in application.properties
 * (management.endpoints.web.exposure.include=...,gateway) from ending up
 * committed, or an env var override reaching a prod pod by mistake — and
 * /actuator/gateway/routes leaks internal routing topology (upstream URIs,
 * path patterns, filters) to anyone who can reach it, including anyone
 * inside the cluster network the port-level lock still allows. This is
 * that second layer: refuse to start entirely under the prod profile
 * rather than silently run exposed. Deliberately a hard failure, not a
 * log warning — a warning is something to notice, a refused startup is
 * something a deploy pipeline can't miss.
 */
@Component
public class ActuatorExposureGuard {

    private final Environment environment;

    public ActuatorExposureGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void verifyGatewayActuatorNotExposedInProd() {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            return; // Local/dev debugging is exactly what the commented-out override is for.
        }

        String exposure = environment.getProperty("management.endpoints.web.exposure.include", "");
        boolean gatewayExposed = Arrays.stream(exposure.split(","))
                .map(String::trim)
                .anyMatch(value -> value.equalsIgnoreCase("gateway") || value.equals("*"));

        if (gatewayExposed) {
            throw new IllegalStateException(
                    "management.endpoints.web.exposure.include includes 'gateway' (or '*') while the "
                            + "'prod' profile is active. /actuator/gateway/** exposes internal routing "
                            + "topology (upstream URIs, path patterns, filters) and must never be reachable "
                            + "in production — see the comment above this property in application.properties. "
                            + "Refusing to start.");
        }
    }
}
