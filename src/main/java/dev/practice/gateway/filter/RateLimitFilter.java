package dev.practice.gateway.filter;

import dev.practice.gateway.client.ApiClient;
import dev.practice.gateway.config.ApiGatewayProperties;
import dev.practice.gateway.config.ApiGatewayProperties.Plan;
import dev.practice.gateway.ratelimit.AdmissionDecision;
import dev.practice.gateway.ratelimit.QuotaPeriod;
import dev.practice.gateway.ratelimit.RateLimiter;
import dev.practice.gateway.web.ErrorResponses;
import dev.practice.gateway.web.GatewayHeaders;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.YearMonth;

/**
 * Step 2: may this client make another request right now / this month?
 * Adds X-RateLimit-* and X-Quota-* headers to every response so well-behaved clients can pace themselves.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    public static final int ORDER = -100;

    private final RateLimiter rateLimiter;
    private final ApiGatewayProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    public RateLimitFilter(RateLimiter rateLimiter, ApiGatewayProperties props, MeterRegistry meters, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ApiClient client = exchange.getAttribute(GatewayHeaders.CLIENT_ATTR);
        if (client == null) {
            // Can't happen with the auth filter in front; refuse rather than let traffic through unlimited.
            return ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "MISSING_API_KEY", "Not authenticated");
        }
        Plan plan = props.plan(client.plan()).orElse(null);
        if (plan == null) {
            return ErrorResponses.write(exchange, HttpStatus.FORBIDDEN, "PLAN_NOT_CONFIGURED",
                    "Plan '" + client.plan() + "' is not configured on this gateway");
        }

        return rateLimiter.admit(client.id(), plan).flatMap(decision -> {
            meters.counter("apigw.admission", "outcome", decision.outcome().name().toLowerCase(),
                    "plan", client.plan()).increment();
            HttpHeaders headers = exchange.getResponse().getHeaders();

            return switch (decision.outcome()) {
                case ALLOWED -> {
                    addLimitHeaders(headers, plan, decision);
                    yield chain.filter(exchange);
                }
                case RATE_LIMITED -> {
                    addLimitHeaders(headers, plan, decision);
                    headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ceilSeconds(decision.retryAfterMs())));
                    yield ErrorResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            "Plan '" + client.plan() + "' allows " + plan.replenishRatePerSecond()
                                    + " requests/second with bursts of " + plan.burstCapacity());
                }
                case QUOTA_EXCEEDED -> {
                    addLimitHeaders(headers, plan, decision);
                    YearMonth month = QuotaPeriod.current(clock);
                    headers.set(HttpHeaders.RETRY_AFTER,
                            String.valueOf(QuotaPeriod.untilEnd(month, clock).toSeconds()));
                    yield ErrorResponses.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                            "Monthly quota of " + plan.monthlyQuota() + " requests used up; resets "
                                    + QuotaPeriod.end(month));
                }
                case UNAVAILABLE -> props.failOpen()
                        // Availability over strictness: a Redis blip shouldn't take every API down.
                        ? chain.filter(exchange)
                        : ErrorResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                                "RATE_LIMITER_UNAVAILABLE", "Please retry shortly");
            };
        });
    }

    private static void addLimitHeaders(HttpHeaders headers, Plan plan, AdmissionDecision d) {
        headers.set(GatewayHeaders.RATE_LIMIT_LIMIT, String.valueOf(plan.burstCapacity()));
        headers.set(GatewayHeaders.RATE_LIMIT_REMAINING, String.valueOf(Math.max(0, d.tokensRemaining())));
        headers.set(GatewayHeaders.QUOTA_LIMIT, String.valueOf(plan.monthlyQuota()));
        headers.set(GatewayHeaders.QUOTA_REMAINING, String.valueOf(Math.max(0, plan.monthlyQuota() - d.quotaUsed())));
    }

    private static long ceilSeconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
