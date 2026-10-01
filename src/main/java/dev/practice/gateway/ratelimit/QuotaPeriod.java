package dev.practice.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Quota periods are calendar months in UTC, the way most API plans bill. */
public final class QuotaPeriod {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private QuotaPeriod() {
    }

    public static YearMonth current(Clock clock) {
        return YearMonth.now(clock.withZone(ZoneOffset.UTC));
    }

    public static String id(YearMonth month) {
        return month.format(MONTH);
    }

    public static Instant end(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Duration untilEnd(YearMonth month, Clock clock) {
        return Duration.between(clock.instant(), end(month));
    }

    /** Redis key: hash-tagged with the client id so it lands in the same Redis Cluster slot as the bucket. */
    public static String quotaKey(String clientId, YearMonth month) {
        return "apigw:rl:{" + clientId + "}:quota:" + id(month);
    }

    public static String bucketKey(String clientId) {
        return "apigw:rl:{" + clientId + "}:bucket";
    }
}
