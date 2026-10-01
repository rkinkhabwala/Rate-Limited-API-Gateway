package dev.practice.gateway.admin;

import dev.practice.gateway.config.ApiGatewayProperties;
import dev.practice.gateway.web.ErrorResponses;
import dev.practice.gateway.web.GatewayHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards /admin/** with a shared admin token. Gateway routes don't pass through here:
 * they're authenticated by API key in {@code ApiKeyAuthFilter}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

    private final byte[] expectedToken;

    public AdminAuthFilter(ApiGatewayProperties props) {
        String token = props.adminToken();
        if (token == null || token.isBlank()) {
            log.warn("apigw.admin-token is empty: the admin API is disabled");
        }
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().pathWithinApplication().value().startsWith("/admin")) {
            return chain.filter(exchange);
        }
        String given = exchange.getRequest().getHeaders().getFirst(GatewayHeaders.ADMIN_TOKEN);
        if (expectedToken.length == 0 || given == null
                // constant-time comparison: response timing doesn't reveal how much of the token matched
                || !MessageDigest.isEqual(expectedToken, given.getBytes(StandardCharsets.UTF_8))) {
            return ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "ADMIN_TOKEN_REQUIRED",
                    "Send a valid " + GatewayHeaders.ADMIN_TOKEN + " header");
        }
        return chain.filter(exchange);
    }
}
