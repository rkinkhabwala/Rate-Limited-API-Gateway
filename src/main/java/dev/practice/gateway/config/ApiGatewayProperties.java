package dev.practice.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigurationProperties(prefix = "apigw")
public record ApiGatewayProperties(
        String adminToken,
        @DefaultValue("true") boolean failOpen,
        Map<String, Plan> plans,
        List<RouteProps> routes) {

    public ApiGatewayProperties {
        plans = plans == null ? Map.of() : Map.copyOf(plans);
        routes = routes == null ? List.of() : List.copyOf(routes);
        plans.forEach((name, plan) -> {
            if (plan.replenishRatePerSecond() <= 0 || plan.burstCapacity() <= 0 || plan.monthlyQuota() <= 0) {
                throw new IllegalArgumentException("Plan '" + name + "' needs positive rate, burst and quota");
            }
        });
    }

    public Optional<Plan> plan(String name) {
        return Optional.ofNullable(plans.get(name));
    }

    /**
     * @param replenishRatePerSecond sustained requests per second
     * @param burstCapacity          max requests in a sudden burst (bucket size)
     * @param monthlyQuota           hard cap per calendar month (UTC)
     */
    public record Plan(int replenishRatePerSecond, int burstCapacity, long monthlyQuota) {
    }

    /**
     * @param stripPrefix how many leading path segments to drop before forwarding
     *                    (/api/orders/42 with 1 → /orders/42)
     */
    public record RouteProps(String id, String path, String uri, @DefaultValue("0") int stripPrefix) {
    }
}
