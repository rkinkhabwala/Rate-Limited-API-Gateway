package dev.practice.gateway.metering;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class UsageMeterStatusBucketTest {

    @Test
    void groupsStatusesIntoClassesButKeeps429Separate() {
        assertThat(UsageMeter.statusBucket(HttpStatus.OK)).isEqualTo("2xx");
        assertThat(UsageMeter.statusBucket(HttpStatus.CREATED)).isEqualTo("2xx");
        assertThat(UsageMeter.statusBucket(HttpStatus.NOT_FOUND)).isEqualTo("4xx");
        assertThat(UsageMeter.statusBucket(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo("429");
        assertThat(UsageMeter.statusBucket(HttpStatus.BAD_GATEWAY)).isEqualTo("5xx");
        assertThat(UsageMeter.statusBucket(null)).isEqualTo("unknown");
    }
}
