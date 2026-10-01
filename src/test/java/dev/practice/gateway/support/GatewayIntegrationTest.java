package dev.practice.gateway.support;

import com.fasterxml.jackson.databind.JsonNode;
import dev.practice.gateway.web.GatewayHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Real gateway on a random port, real Redis in a container, fake upstream on the JDK HTTP server.
 * <p>
 * The container is started once and shared by all test classes (the "singleton container" pattern),
 * because the Spring context, which caches Redis' host/port, is shared across classes too.
 * Concrete classes carry {@code @Testcontainers(disabledWithoutDocker = true)}.
 * <p>
 * Each test creates its own client, so buckets and quotas never leak between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "apigw.admin-token=" + GatewayIntegrationTest.ADMIN_TOKEN,
        // small burst so throttling is easy to trigger
        "apigw.plans.burst.replenish-rate-per-second=1",
        "apigw.plans.burst.burst-capacity=3",
        "apigw.plans.burst.monthly-quota=1000000",
        // tiny monthly quota
        "apigw.plans.quota.replenish-rate-per-second=100",
        "apigw.plans.quota.burst-capacity=100",
        "apigw.plans.quota.monthly-quota=3",
        // effectively unlimited, for tests about something else
        "apigw.plans.roomy.replenish-rate-per-second=1000",
        "apigw.plans.roomy.burst-capacity=1000",
        "apigw.plans.roomy.monthly-quota=1000000"
})
public abstract class GatewayIntegrationTest {

    public static final String ADMIN_TOKEN = "test-admin-token";

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    protected static final StubUpstream UPSTREAM = StubUpstream.start();

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Replaces the httpbin route from application.yml: tests never touch the internet.
        registry.add("apigw.routes[0].id", () -> "orders");
        registry.add("apigw.routes[0].path", () -> "/api/orders/**");
        registry.add("apigw.routes[0].uri", UPSTREAM::baseUrl);
        registry.add("apigw.routes[0].strip-prefix", () -> "1");
    }

    @Autowired
    protected WebTestClient web;

    @LocalServerPort
    protected int port;

    protected record TestClient(String id, String apiKey) {
    }

    protected TestClient createClient(String plan) {
        JsonNode body = web.post().uri("/admin/clients")
                .header(GatewayHeaders.ADMIN_TOKEN, ADMIN_TOKEN)
                .bodyValue(Map.of("name", "test client", "plan", plan))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
        return new TestClient(body.at("/client/id").asText(), body.get("apiKey").asText());
    }

    protected WebTestClient.ResponseSpec call(TestClient client, String path) {
        return web.get().uri(path).header(GatewayHeaders.API_KEY, client.apiKey()).exchange();
    }

    protected JsonNode admin(String path) {
        return web.get().uri(path)
                .header(GatewayHeaders.ADMIN_TOKEN, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .expectBody(JsonNode.class)
                .returnResult().getResponseBody();
    }

    /** Metering is written just after the response goes out, so give it a moment. */
    protected static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 3s");
            }
            Thread.sleep(50);
        }
    }
}
