package dev.practice.gateway.filter;

import dev.practice.gateway.client.ApiClient;
import dev.practice.gateway.client.ApiClientStore;
import dev.practice.gateway.web.ErrorResponses;
import dev.practice.gateway.web.GatewayHeaders;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Step 1 for every routed request: who is calling?
 * <ul>
 *   <li>Missing / unknown / revoked key → 401, the request never reaches the upstream.</li>
 *   <li>Valid key → the key is stripped (upstreams never see secrets) and trusted identity headers
 *       are set, overwriting anything the caller tried to spoof.</li>
 * </ul>
 */
@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    public static final int ORDER = -200;

    private final ApiClientStore clients;

    public ApiKeyAuthFilter(ApiClientStore clients) {
        this.clients = clients;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            return ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "MISSING_API_KEY",
                    "Send your API key in the " + GatewayHeaders.API_KEY + " header");
        }

        // Reactive gotcha: chain.filter() returns an EMPTY Mono<Void> when it succeeds, so
        // `lookup.flatMap(chain...).switchIfEmpty(reject)` would reject every successful request.
        // Wrapping in Optional keeps "client not found" and "request finished" apart.
        return clients.findByApiKey(apiKey)
                .filter(ApiClient::isActive)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(found -> found
                        .map(client -> forward(exchange, chain, client))
                        .orElseGet(() -> ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY",
                                "The API key is unknown or has been revoked")));
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, ApiClient client) {
        exchange.getAttributes().put(GatewayHeaders.CLIENT_ATTR, client);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(GatewayHeaders.API_KEY);
                    h.set(GatewayHeaders.CLIENT_ID, client.id());
                    h.set(GatewayHeaders.CLIENT_PLAN, client.plan());
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
