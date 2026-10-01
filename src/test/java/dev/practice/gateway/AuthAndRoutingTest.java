package dev.practice.gateway;

import dev.practice.gateway.support.GatewayIntegrationTest;
import dev.practice.gateway.web.GatewayHeaders;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AuthAndRoutingTest extends GatewayIntegrationTest {

    @Test
    void missingKeyIsRejectedBeforeReachingUpstream() {
        int before = UPSTREAM.hits();

        web.get().uri("/api/orders/1").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo("MISSING_API_KEY");

        assertThat(UPSTREAM.hits()).isEqualTo(before);
    }

    @Test
    void unknownKeyIsRejected() {
        web.get().uri("/api/orders/1")
                .header(GatewayHeaders.API_KEY, "gwk_" + "x".repeat(43))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_API_KEY");
    }

    @Test
    void validKeyIsForwardedWithTrustedIdentityAndWithoutTheSecret() {
        TestClient client = createClient("roomy");

        web.get().uri("/api/orders/42")
                .header(GatewayHeaders.API_KEY, client.apiKey())
                .header(GatewayHeaders.CLIENT_ID, "admin") // spoofing attempt
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(GatewayHeaders.RATE_LIMIT_REMAINING)
                .expectHeader().exists(GatewayHeaders.QUOTA_REMAINING)
                .expectBody()
                .jsonPath("$.path").isEqualTo("/orders/42")          // /api prefix stripped
                .jsonPath("$.clientId").isEqualTo(client.id())        // spoofed value overwritten
                .jsonPath("$.clientPlan").isEqualTo("roomy")
                .jsonPath("$.apiKeyForwarded").isEqualTo(false);      // secret never leaves the gateway
    }

    @Test
    void revokedKeyStopsWorkingImmediately() {
        TestClient client = createClient("roomy");
        call(client, "/api/orders/1").expectStatus().isOk();

        web.delete().uri("/admin/clients/{id}", client.id())
                .header(GatewayHeaders.ADMIN_TOKEN, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isNoContent();

        call(client, "/api/orders/1").expectStatus().isUnauthorized();
        assertThat(admin("/admin/clients/" + client.id()).get("status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void rotatingAKeyKillsTheOldOne() {
        TestClient client = createClient("roomy");

        String newKey = web.post().uri("/admin/clients/{id}/rotate-key", client.id())
                .header(GatewayHeaders.ADMIN_TOKEN, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody(com.fasterxml.jackson.databind.JsonNode.class)
                .returnResult().getResponseBody().get("apiKey").asText();

        call(client, "/api/orders/1").expectStatus().isUnauthorized();
        call(new TestClient(client.id(), newKey), "/api/orders/1").expectStatus().isOk();
    }

    @Test
    void adminApiRequiresTheAdminToken() {
        web.post().uri("/admin/clients")
                .bodyValue(Map.of("name", "x", "plan", "free"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("ADMIN_TOKEN_REQUIRED");

        web.post().uri("/admin/clients")
                .header(GatewayHeaders.ADMIN_TOKEN, "wrong")
                .bodyValue(Map.of("name", "x", "plan", "free"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void creatingAClientOnAnUnknownPlanFails() {
        web.post().uri("/admin/clients")
                .header(GatewayHeaders.ADMIN_TOKEN, ADMIN_TOKEN)
                .bodyValue(Map.of("name", "x", "plan", "platinum"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void plaintextKeyIsNeverReturnedAgain() {
        TestClient client = createClient("free");

        String json = web.get().uri("/admin/clients/{id}", client.id())
                .header(GatewayHeaders.ADMIN_TOKEN, ADMIN_TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(json).doesNotContain(client.apiKey()).doesNotContain("keyHash");
    }
}
