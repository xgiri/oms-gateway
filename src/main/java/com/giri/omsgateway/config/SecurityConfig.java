package com.giri.omsgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The gateway is now the browser-facing edge — Angular talks to it, not
 * directly to oms-main anymore — so CORS and JWT verification both move
 * here. oms-main's own SecurityConfig/CorsConfig are left exactly as they
 * are: they keep validating every request independently (defense in depth),
 * they just no longer need to be reachable from the browser directly once
 * Angular is repointed at the gateway.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private List<String> allowedOrigins;

    // Same public-paths shape as oms-main's own SecurityConfig
    // (PUBLIC_DOC_PATHS + the login/JWKS/health exceptions) — kept in sync
    // deliberately so the gateway's edge policy doesn't drift from the
    // monolith's, which still enforces its own copy of this list too.
    // /graphiql is the one addition oms-main doesn't have: oms-bff's own
    // dev-only explorer page, routed through here now too (see
    // application.properties' oms-bff route) - it's static HTML/JS, and the
    // queries it submits still POST to /graphql, which is NOT in this list
    // and still requires a valid JWT.
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/.well-known/jwks.json",
            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
            "/docs", "/docs/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
            // shipment-service's docs, proxied under its own path prefix (see
            // application.properties routes[5]) — same reasoning as the
            // bare oms-main doc paths directly above: static/public docs,
            // no JWT needed to view them.
            "/shipment-service/docs", "/shipment-service/docs/**",
            "/shipment-service/v3/api-docs/**", "/shipment-service/swagger-ui/**",
            "/shipment-service/swagger-ui.html",
            "/graphiql", "/graphiql/**"
    };

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API, same rationale as oms-main
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(auth -> auth
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
                // Gateway's own auth failures (missing/invalid/expired token)
                // should look like oms-main's — a plain 401, not a redirect
                // to a login page that doesn't exist in a stateless API.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().setRawStatusCode(401);
                            return exchange.getResponse().setComplete();
                        })
                );

        return http.build();
    }

    /**
     * CORS now has to be answered at the gateway, since it's the origin the
     * browser actually talks to — oms-main's own CorsConfig stays in place
     * but is moot for browser traffic once Angular is repointed here.
     * Reuses the same CORS_ALLOWED_ORIGINS convention as oms-main so both
     * apps are configured from the same env var name in every environment.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}