package dev.practice.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterUnitTest {

    @Test
    void flattensBothShapesOfALuaArrayReply() {
        assertThat(RateLimiter.flatten(List.of(1L, 2L, 0L, 5L))).containsExactly(1L, 2L, 0L, 5L);
        assertThat(RateLimiter.flatten(List.of(List.of(1L, 2L, 0L, 5L)))).containsExactly(1L, 2L, 0L, 5L);
        assertThat(RateLimiter.flatten(List.of("1".getBytes(), 2, "0", 5L))).containsExactly(1L, 2L, 0L, 5L);
    }

    @Test
    void quotaPeriodIsTheUtcCalendarMonth() {
        // 23:30 on Nov 30 in New York is already December in UTC.
        Clock clock = Clock.fixed(Instant.parse("2026-12-01T04:30:00Z"), ZoneOffset.UTC);

        YearMonth month = QuotaPeriod.current(clock);

        assertThat(QuotaPeriod.id(month)).isEqualTo("202612");
        assertThat(QuotaPeriod.end(month)).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void bucketAndQuotaKeysShareARedisClusterHashTag() {
        String bucket = QuotaPeriod.bucketKey("cli_42");
        String quota = QuotaPeriod.quotaKey("cli_42", YearMonth.of(2026, 11));

        assertThat(bucket).contains("{cli_42}");
        assertThat(quota).contains("{cli_42}").endsWith("202611");
    }
}
