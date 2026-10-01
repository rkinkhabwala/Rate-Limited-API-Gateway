package dev.practice.gateway.ratelimit;

import dev.practice.gateway.config.ApiGatewayProperties.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Distributed token bucket + monthly quota, evaluated atomically in Redis (see scripts/admit.lua).
 * Every gateway instance shares the same counters, so limits hold no matter how many instances run.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADMIT = RedisScript.of(new ClassPathResource("scripts/admit.lua"), List.class);

    /** Keep the counter a week past month end, so late reports and disputes can still read it. */
    private static final Duration QUOTA_GRACE = Duration.ofDays(7);

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Never errors: Redis failures become {@link AdmissionDecision.Outcome#UNAVAILABLE}. */
    public Mono<AdmissionDecision> admit(String clientId, Plan plan) {
        YearMonth month = QuotaPeriod.current(clock);
        long quotaTtlSeconds = QuotaPeriod.untilEnd(month, clock).plus(QUOTA_GRACE).toSeconds();

        List<String> keys = List.of(QuotaPeriod.bucketKey(clientId), QuotaPeriod.quotaKey(clientId, month));
        List<String> args = List.of(
                String.valueOf(plan.burstCapacity()),
                String.valueOf(plan.replenishRatePerSecond()),
                String.valueOf(plan.monthlyQuota()),
                String.valueOf(quotaTtlSeconds),
                "1");

        return redis.execute(ADMIT, keys, args)
                .collectList()
                .map(RateLimiter::flatten)
                .map(RateLimiter::toDecision)
                .onErrorResume(e -> {
                    log.warn("Rate limiter unavailable for client {}: {}", clientId, e.toString());
                    return Mono.just(AdmissionDecision.unavailable());
                });
    }

    /** Reads this month's usage without consuming anything (for reports). */
    public Mono<Long> quotaUsed(String clientId) {
        return redis.opsForValue().get(QuotaPeriod.quotaKey(clientId, QuotaPeriod.current(clock)))
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }

    private static AdmissionDecision toDecision(List<Long> r) {
        if (r.size() != 4) {
            throw new IllegalStateException("Unexpected admit.lua result: " + r);
        }
        AdmissionDecision.Outcome outcome = switch (r.get(0).intValue()) {
            case 1 -> AdmissionDecision.Outcome.ALLOWED;
            case 0 -> AdmissionDecision.Outcome.RATE_LIMITED;
            case 2 -> AdmissionDecision.Outcome.QUOTA_EXCEEDED;
            default -> throw new IllegalStateException("Unknown outcome " + r.get(0));
        };
        return new AdmissionDecision(outcome, r.get(1), r.get(2), r.get(3));
    }

    /**
     * A Lua array reply may arrive as one List or as its elements one by one depending on the
     * driver; normalise both shapes to a flat list of longs.
     */
    static List<Long> flatten(List<?> raw) {
        List<Long> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof List<?> inner) {
                inner.forEach(x -> out.add(toLong(x)));
            } else {
                out.add(toLong(o));
            }
        }
        return out;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes));
        }
        return Long.parseLong(String.valueOf(o));
    }
}
