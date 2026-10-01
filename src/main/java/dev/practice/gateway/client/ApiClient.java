package dev.practice.gateway.client;

import java.time.Instant;

/**
 * A consumer of the API. The plaintext key is never stored: only its SHA-256 hash (for lookup)
 * and a short prefix (so humans can tell keys apart in dashboards and logs).
 */
public record ApiClient(
        String id,
        String name,
        String plan,
        String keyPrefix,
        String keyHash,
        ClientStatus status,
        Instant createdAt) {

    public boolean isActive() {
        return status == ClientStatus.ACTIVE;
    }
}
