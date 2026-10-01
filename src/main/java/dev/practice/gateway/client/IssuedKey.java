package dev.practice.gateway.client;

/** Returned exactly once, at creation or rotation. The plaintext key is unrecoverable afterwards. */
public record IssuedKey(ApiClient client, String apiKey) {
}
