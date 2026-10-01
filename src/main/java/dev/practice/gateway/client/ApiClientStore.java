package dev.practice.gateway.client;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.security.SecureRandom;

/**
 * Redis layout:
 * <pre>
 *   apigw:client:{id}        hash   name, plan, keyPrefix, keyHash, status, createdAt
 *   apigw:apikey:{sha256}    string client id   (lookup index used on every request)
 * </pre>
 */
@Component
public class ApiClientStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;

    public ApiClientStore(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public Mono<IssuedKey> create(String name, String plan) {
        String id = "cli_" + HexFormat.of().formatHex(randomBytes(8));
        String apiKey = ApiKeys.generate();
        ApiClient client = new ApiClient(id, name, plan, ApiKeys.displayPrefix(apiKey), ApiKeys.hash(apiKey),
                ClientStatus.ACTIVE, clock.instant());
        return redis.opsForHash().putAll(clientKey(id), toHash(client))
                .then(redis.opsForValue().set(indexKey(client.keyHash()), id))
                .thenReturn(new IssuedKey(client, apiKey));
    }

    public Mono<ApiClient> findById(String id) {
        return redis.<String, String>opsForHash().entries(clientKey(id))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(map -> !map.isEmpty())
                .map(map -> fromHash(id, map));
    }

    /** Empty if the key is unknown or malformed. Revoked clients ARE returned; the caller decides. */
    public Mono<ApiClient> findByApiKey(String apiKey) {
        if (!ApiKeys.looksValid(apiKey)) {
            return Mono.empty(); // don't even hit Redis for garbage
        }
        return redis.opsForValue().get(indexKey(ApiKeys.hash(apiKey)))
                .flatMap(this::findById);
    }

    /** Issues a new key and kills the old one immediately. */
    public Mono<IssuedKey> rotateKey(String id) {
        return findById(id)
                .filter(ApiClient::isActive)
                .flatMap(old -> {
                    String apiKey = ApiKeys.generate();
                    ApiClient updated = new ApiClient(old.id(), old.name(), old.plan(), ApiKeys.displayPrefix(apiKey),
                            ApiKeys.hash(apiKey), old.status(), old.createdAt());
                    return redis.opsForHash().putAll(clientKey(id), toHash(updated))
                            .then(redis.opsForValue().set(indexKey(updated.keyHash()), id))
                            .then(redis.delete(indexKey(old.keyHash())))
                            .thenReturn(new IssuedKey(updated, apiKey));
                });
    }

    /** @return true if the client existed */
    public Mono<Boolean> revoke(String id) {
        return findById(id)
                .flatMap(client -> redis.opsForHash().put(clientKey(id), "status", ClientStatus.REVOKED.name())
                        .then(redis.delete(indexKey(client.keyHash())))
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    private static Map<String, String> toHash(ApiClient c) {
        Map<String, String> map = new HashMap<>();
        map.put("name", c.name());
        map.put("plan", c.plan());
        map.put("keyPrefix", c.keyPrefix());
        map.put("keyHash", c.keyHash());
        map.put("status", c.status().name());
        map.put("createdAt", c.createdAt().toString());
        return map;
    }

    private static ApiClient fromHash(String id, Map<String, String> m) {
        return new ApiClient(id, m.get("name"), m.get("plan"), m.get("keyPrefix"), m.get("keyHash"),
                ClientStatus.valueOf(m.get("status")), Instant.parse(m.get("createdAt")));
    }

    private static String clientKey(String id) {
        return "apigw:client:" + id;
    }

    private static String indexKey(String keyHash) {
        return "apigw:apikey:" + keyHash;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
