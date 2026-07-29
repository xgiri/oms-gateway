package com.giri.omsgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * No Spring context needed — {@link ActuatorExposureGuard} only reads two
 * things off {@link org.springframework.core.env.Environment}, so a
 * {@link MockEnvironment} exercises the real decision logic without the
 * cost (or the network/Vault dependencies) of a full context load.
 */
class ActuatorExposureGuardTest {

    @Test
    void refusesToStartWhenGatewayIsExposedInProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("management.endpoints.web.exposure.include", "health,info,prometheus,gateway");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ActuatorExposureGuard(environment).verifyGatewayActuatorNotExposedInProd());
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("gateway"));
    }

    @Test
    void refusesToStartWhenWildcardExposureIsUsedInProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("management.endpoints.web.exposure.include", "*");

        assertThrows(IllegalStateException.class,
                () -> new ActuatorExposureGuard(environment).verifyGatewayActuatorNotExposedInProd());
    }

    @Test
    void allowsStartupInProdWhenGatewayIsNotExposed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("management.endpoints.web.exposure.include", "health,info,prometheus");

        assertDoesNotThrow(() -> new ActuatorExposureGuard(environment).verifyGatewayActuatorNotExposedInProd());
    }

    @Test
    void allowsGatewayExposureOutsideProdForLocalDebugging() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("management.endpoints.web.exposure.include", "health,info,prometheus,gateway");

        assertDoesNotThrow(() -> new ActuatorExposureGuard(environment).verifyGatewayActuatorNotExposedInProd());
    }
}
