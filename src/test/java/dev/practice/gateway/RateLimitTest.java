package dev.practice.gateway;

import dev.practice.gateway.support.GatewayIntegrationTest;
import dev.practice.gateway.web.GatewayHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan "burst": bucket of 3, refills 1 token per second. */
@Testcontainers(disabledWithoutDocker = true)
class RateLimitTest extends GatewayIntegrationTest {

    @Test
    void burstIsAllowedThenThrottledThenRefills() throws Exception {
        TestClient client = createClient("burst");
        int before = UPSTREAM.hits();

        call(client, "/api/orders/1").expectStatus().isOk()
                .expectHeader().valueEquals(GatewayHeaders.RATE_LIMIT_LIMIT, "3")
                .expectHeader().valueEquals(GatewayHeaders.RATE_LIMIT_REMAINING, "2");
        call(client, "/api/orders/1").expectStatus().isOk()
                .expectHeader().valueEquals(GatewayHeaders.RATE_LIMIT_REMAINING, "1");
        call(client, "/api/orders/1").expectStatus().isOk()
                .expectHeader().valueEquals(GatewayHeaders.RATE_LIMIT_REMAINING, "0");

        call(client, "/api/orders/1").expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");

        assertThat(UPSTREAM.hits() - before).as("throttled request must not reach upstream").isEqualTo(3);

        Thread.sleep(1_100); // one token refills per second
        call(client, "/api/orders/1").expectStatus().isOk();
    }

    @Test
    void eachClientHasItsOwnBucket() {
        TestClient noisy = createClient("burst");
        TestClient quiet = createClient("burst");

        for (int i = 0; i < 3; i++) {
            call(noisy, "/api/orders/1").expectStatus().isOk();
        }
        call(noisy, "/api/orders/1").expectStatus().isEqualTo(429);

        call(quiet, "/api/orders/1").expectStatus().isOk();
    }

    @Test
    void parallelRequestsCannotSneakPastTheLimit() {
        TestClient client = createClient("burst");
        WebClient http = WebClient.create("http://localhost:" + port);

        // 20 requests in flight at once. A naive GET-then-SET limiter would let several extra through;
        // the Lua script makes check-and-consume a single atomic step.
        List<Integer> statuses = Flux.range(0, 20)
                .flatMap(i -> http.get().uri("/api/orders/1")
                        .header(GatewayHeaders.API_KEY, client.apiKey())
                        .exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value())), 20)
                .collectList()
                .block(Duration.ofSeconds(30));

        Map<Integer, Long> counts = statuses.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(counts).containsOnlyKeys(200, 429);
        // 3 from the full bucket; at most 1 more if the burst happened to straddle a refill.
        assertThat(counts.get(200)).isBetween(3L, 4L);
    }
}
