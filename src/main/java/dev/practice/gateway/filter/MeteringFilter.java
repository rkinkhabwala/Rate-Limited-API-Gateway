package dev.practice.gateway.filter;

import dev.practice.gateway.client.ApiClient;
import dev.practice.gateway.metering.UsageMeter;
import dev.practice.gateway.web.GatewayHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Outermost filter: lets everything inside run, then records the final status, including 429s
 * produced by {@link RateLimitFilter}. Unauthenticated requests aren't metered (no client to bill).
 * Metering failures are logged, never surfaced: losing a counter must not fail a customer's request.
 */
@Component
public class MeteringFilter implements GlobalFilter, Ordered {

    public static final int ORDER = -300;
    private static final Logger log = LoggerFactory.getLogger(MeteringFilter.class);

    private final UsageMeter meter;

    public MeteringFilter(UsageMeter meter) {
        this.meter = meter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.defer(() -> {
            ApiClient client = exchange.getAttribute(GatewayHeaders.CLIENT_ATTR);
            if (client == null) {
                return Mono.empty();
            }
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routeId = route == null ? "unknown" : route.getId();
            return meter.record(client.id(), routeId, exchange.getResponse().getStatusCode())
                    .onErrorResume(e -> {
                        log.warn("Could not meter request for {}: {}", client.id(), e.toString());
                        return Mono.empty();
                    });
        }));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
