package dev.practice.gateway.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Key format: {@code gwk_} + 43 chars of URL-safe base64 (256 bits of randomness).
 * The recognisable prefix lets secret scanners (GitHub, GitGuardian) catch leaked keys.
 * <p>
 * Plain SHA-256 is the right hash here, unlike passwords: the input is already high-entropy random,
 * so brute-forcing it is hopeless, and a fast hash keeps per-request lookup cheap.
 */
public final class ApiKeys {

    public static final String PREFIX = "gwk_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** First 8 characters after the prefix, e.g. "gwk_Ab3dE9xY". Safe to show and log. */
    public static String displayPrefix(String apiKey) {
        return apiKey.substring(0, Math.min(apiKey.length(), PREFIX.length() + 8));
    }

    public static boolean looksValid(String apiKey) {
        return apiKey != null && apiKey.startsWith(PREFIX) && apiKey.length() == PREFIX.length() + 43;
    }
}
