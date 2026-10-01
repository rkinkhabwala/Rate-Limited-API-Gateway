package dev.practice.gateway.metering;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts every request per client, per day, per route and status class, for billing and dashboards.
 * <pre>
 *   apigw:usage:{clientId}:{yyyy-MM-dd}   hash   "orders|2xx" → 1520, "orders|429" → 33, ...
 * </pre>
 * Kept 90 days. Hash fields stay few (routes × status classes), so one HGETALL per day is cheap.
 */
@Component
public class UsageMeter {

    private static final Duration RETENTION = Duration.ofDays(90);

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;

    public UsageMeter(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public Mono<Void> record(String clientId, String routeId, HttpStatusCode status) {
        String key = dayKey(clientId, today());
        String field = routeId + "|" + statusBucket(status);
        return redis.opsForHash().increment(key, field, 1)
                .then(redis.expire(key, RETENTION))
                .then();
    }

    /** Oldest day first; days without traffic are included with zero totals. */
    public Flux<DailyUsage> lastDays(String clientId, int days) {
        LocalDate today = today();
        return Flux.range(0, days)
                .map(i -> today.minusDays(days - 1L - i))
                .concatMap(date -> redis.<String, String>opsForHash().entries(dayKey(clientId, date))
                        .collectMap(Map.Entry::getKey, e -> Long.parseLong(e.getValue()), TreeMap::new)
                        .map(counts -> new DailyUsage(date, counts.values().stream().mapToLong(Long::longValue).sum(),
                                counts)));
    }

    /** 429s are kept separate from other 4xx: they're the number customers ask about. */
    static String statusBucket(HttpStatusCode status) {
        if (status == null) {
            return "unknown";
        }
        if (status.value() == 429) {
            return "429";
        }
        return (status.value() / 100) + "xx";
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static String dayKey(String clientId, LocalDate date) {
        return "apigw:usage:" + clientId + ":" + date;
    }

    public record DailyUsage(LocalDate date, long total, Map<String, Long> byRouteAndStatus) {
    }
}
