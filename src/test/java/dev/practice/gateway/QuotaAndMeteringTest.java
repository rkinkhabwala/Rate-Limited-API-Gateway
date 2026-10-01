package dev.practice.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import dev.practice.gateway.support.GatewayIntegrationTest;
import dev.practice.gateway.web.GatewayHeaders;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan "quota": generous rate, but only 3 requests per month. */
@Testcontainers(disabledWithoutDocker = true)
class QuotaAndMeteringTest extends GatewayIntegrationTest {

    @Test
    void monthlyQuotaIsEnforcedAndReported() throws Exception {
        TestClient client = createClient("quota");

        call(client, "/api/orders/1").expectStatus().isOk()
                .expectHeader().valueEquals(GatewayHeaders.QUOTA_REMAINING, "2");
        call(client, "/api/orders/1").expectStatus().isOk()
                .expectHeader().valueEquals(GatewayHeaders.QUOTA_REMAINING, "1");
        call(client, "/api/orders/1").expectStatus().isOk()
                .expectHeader().valueEquals(GatewayHeaders.QUOTA_REMAINING, "0");

        call(client, "/api/orders/1").expectStatus().isEqualTo(429)
                .expectHeader().exists("Retry-After")
                .expectBody().jsonPath("$.code").isEqualTo("QUOTA_EXCEEDED");

        // A rejected request doesn't burn quota.
        JsonNode report = admin("/admin/clients/" + client.id() + "/usage?days=1");
        assertThat(report.at("/quota/limit").asLong()).isEqualTo(3);
        assertThat(report.at("/quota/used").asLong()).isEqualTo(3);
        assertThat(report.at("/quota/remaining").asLong()).isZero();

        eventually(() -> {
            JsonNode today = admin("/admin/clients/" + client.id() + "/usage?days=1").at("/days/0");
            return today.at("/byRouteAndStatus/orders|2xx").asLong() == 3
                    && today.at("/byRouteAndStatus/orders|429").asLong() == 1
                    && today.get("total").asLong() == 4;
        });
    }

    @Test
    void upstreamErrorsAreMeteredSeparately() throws Exception {
        TestClient client = createClient("roomy");

        call(client, "/api/orders/fail").expectStatus().isEqualTo(500);
        call(client, "/api/orders/ok").expectStatus().isOk();

        eventually(() -> {
            JsonNode today = admin("/admin/clients/" + client.id() + "/usage?days=1").at("/days/0");
            return today.at("/byRouteAndStatus/orders|5xx").asLong() == 1
                    && today.at("/byRouteAndStatus/orders|2xx").asLong() == 1;
        });
    }

    @Test
    void usageReportCoversRequestedDaysOldestFirst() {
        TestClient client = createClient("roomy");

        JsonNode report = admin("/admin/clients/" + client.id() + "/usage?days=7");

        assertThat(report.get("days")).hasSize(7);
        assertThat(report.at("/days/6/total").asLong()).isZero();
        assertThat(report.at("/days/0/date").asText()).isLessThan(report.at("/days/6/date").asText());
    }
}
