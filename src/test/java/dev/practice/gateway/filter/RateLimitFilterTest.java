package dev.practice.gateway.filter;

import dev.practice.gateway.client.ApiClient;
import dev.practice.gateway.client.ClientStatus;
import dev.practice.gateway.config.ApiGatewayProperties;
import dev.practice.gateway.config.ApiGatewayProperties.Plan;
import dev.practice.gateway.ratelimit.AdmissionDecision;
import dev.practice.gateway.ratelimit.AdmissionDecision.Outcome;
import dev.practice.gateway.ratelimit.RateLimiter;
import dev.practice.gateway.web.GatewayHeaders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit tests of the filter's decisions. No Spring context, no Redis, no Docker. */
class RateLimitFilterTest {

    private static final Plan FREE = new Plan(1, 2, 100);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final AtomicBoolean upstreamCalled = new AtomicBoolean();
    private final GatewayFilterChain chain = exchange -> {
        upstreamCalled.set(true);
        return Mono.empty();
    };

    @Test
    void allowedRequestPassesWithLimitHeaders() {
        var exchange = exchangeWithClient();
        decide(new AdmissionDecision(Outcome.ALLOWED, 1, 0, 10));

        filter(true).filter(exchange, chain).block();

        assertThat(upstreamCalled).isTrue();
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayHeaders.RATE_LIMIT_REMAINING)).isEqualTo("1");
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayHeaders.QUOTA_REMAINING)).isEqualTo("90");
    }

    @Test
    void rateLimitedRequestGets429WithRoundedUpRetryAfter() {
        var exchange = exchangeWithClient();
        decide(new AdmissionDecision(Outcome.RATE_LIMITED, 0, 1_500, 10));

        filter(true).filter(exchange, chain).block();

        assertThat(upstreamCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"RATE_LIMITED\"");
    }

    @Test
    void quotaExceededRetryAfterPointsToNextMonth() {
        var exchange = exchangeWithClient();
        decide(new AdmissionDecision(Outcome.QUOTA_EXCEEDED, 0, 0, 100));

        filter(true).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // 2026-11-15T10:00Z → 2026-12-01T00:00Z
        long expected = java.time.Duration.between(CLOCK.instant(), Instant.parse("2026-12-01T00:00:00Z")).toSeconds();
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo(String.valueOf(expected));
    }

    @Test
    void failOpenLetsTrafficThroughWhenRedisIsDown() {
        var exchange = exchangeWithClient();
        decide(AdmissionDecision.unavailable());

        filter(true).filter(exchange, chain).block();

        assertThat(upstreamCalled).isTrue();
    }

    @Test
    void failClosedReturns503WhenRedisIsDown() {
        var exchange = exchangeWithClient();
        decide(AdmissionDecision.unavailable());

        filter(false).filter(exchange, chain).block();

        assertThat(upstreamCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void clientOnARemovedPlanIsForbidden() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders/1"));
        exchange.getAttributes().put(GatewayHeaders.CLIENT_ATTR, client("legacy-gold"));

        filter(true).filter(exchange, chain).block();

        assertThat(upstreamCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ helpers

    private RateLimitFilter filter(boolean failOpen) {
        var props = new ApiGatewayProperties("token", failOpen, Map.of("free", FREE), List.of());
        return new RateLimitFilter(limiter, props, new SimpleMeterRegistry(), CLOCK);
    }

    private void decide(AdmissionDecision decision) {
        when(limiter.admit(anyString(), any())).thenReturn(Mono.just(decision));
    }

    private static MockServerWebExchange exchangeWithClient() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders/1"));
        exchange.getAttributes().put(GatewayHeaders.CLIENT_ATTR, client("free"));
        return exchange;
    }

    private static ApiClient client(String plan) {
        return new ApiClient("cli_test", "Test", plan, "gwk_abcd1234", "hash", ClientStatus.ACTIVE, CLOCK.instant());
    }
}
