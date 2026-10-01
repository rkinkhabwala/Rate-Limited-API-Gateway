package dev.practice.gateway.admin;

import dev.practice.gateway.client.ApiClient;
import dev.practice.gateway.client.IssuedKey;
import dev.practice.gateway.metering.UsageMeter.DailyUsage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request/response shapes for the admin API. keyHash never leaves the server. */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateClientRequest(@NotBlank @Size(max = 100) String name, @NotBlank String plan) {
    }

    public record ClientResponse(String id, String name, String plan, String keyPrefix, String status,
                                 Instant createdAt) {
        public static ClientResponse from(ApiClient c) {
            return new ClientResponse(c.id(), c.name(), c.plan(), c.keyPrefix(), c.status().name(), c.createdAt());
        }
    }

    /** The only response that ever contains the plaintext key. */
    public record IssuedKeyResponse(ClientResponse client, String apiKey, String warning) {
        public static IssuedKeyResponse from(IssuedKey issued) {
            return new IssuedKeyResponse(ClientResponse.from(issued.client()), issued.apiKey(),
                    "Store this key now. It cannot be shown again.");
        }
    }

    public record QuotaStatus(String period, long limit, long used, long remaining, Instant resetsAt) {
    }

    public record UsageReport(String clientId, String plan, QuotaStatus quota, List<DailyUsage> days) {
    }
}
