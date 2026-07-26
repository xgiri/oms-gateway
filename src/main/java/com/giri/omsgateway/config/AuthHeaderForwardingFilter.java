package com.giri.omsgateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Adds {@code X-Auth-User} / {@code X-Auth-Roles} to every downstream
 * request, populated from the JWT this gateway already validated (see
 * SecurityConfig's {@code oauth2ResourceServer} config). This is option (b)
 * from oms-bff's SecurityConfig TODO: oms-bff sits fully behind this
 * gateway's network, so it trusts these headers unconditionally rather than
 * re-verifying the JWT itself a second time — auth stays single-source-of-
 * truth (the JWT, checked once, here) instead of re-checked at every hop.
 * <p>
 * This filter only ever *adds* headers — it never strips or overwrites
 * {@code Authorization} — so oms-main's route is unaffected: it keeps
 * seeing (and independently re-validating) the original bearer token
 * exactly as before, defense in depth intact.
 * <p>
 * Requests with no authenticated principal (the public routes — login,
 * JWKS, docs) simply pass through unmodified; there's no claims to forward.
 */
@Component
public class AuthHeaderForwardingFilter implements GlobalFilter, Ordered {

    public static final String USER_HEADER = "X-Auth-User";
    public static final String ROLES_HEADER = "X-Auth-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .map(jwt -> withAuthHeaders(exchange, jwt))
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    private ServerWebExchange withAuthHeaders(ServerWebExchange exchange, Jwt jwt) {
        List<String> authorities = jwt.getClaimAsStringList("authorities");
        String roles = authorities == null ? "" : String.join(",", authorities);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(USER_HEADER, jwt.getSubject());
                    headers.set(ROLES_HEADER, roles);
                })
                .build();

        return exchange.mutate().request(mutatedRequest).build();
    }

    @Override
    public int getOrder() {
        // Must run before NettyRoutingFilter (order = LOWEST_PRECEDENCE,
        // the filter that actually dispatches the request downstream) so
        // the mutated headers are still on the request by the time it's
        // sent. Runs after Spring Security's own WebFilter, which executes
        // ahead of the gateway's GlobalFilter chain and is what populates
        // the security context this filter reads.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
