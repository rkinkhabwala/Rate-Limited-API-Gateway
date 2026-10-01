package dev.practice.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    /** Routes come from apigw.routes so adding a backend is a config change, not a code change. */
    @Bean
    public RouteLocator apiRoutes(RouteLocatorBuilder builder, ApiGatewayProperties props) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (ApiGatewayProperties.RouteProps r : props.routes()) {
            routes = routes.route(r.id(), p -> p.path(r.path())
                    .filters(f -> f.stripPrefix(r.stripPrefix()))
                    .uri(r.uri()));
        }
        return routes.build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
