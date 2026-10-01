package dev.practice.gateway.client;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    void generatedKeysHaveRecognisablePrefixAndFixedLength() {
        String key = ApiKeys.generate();

        assertThat(key).startsWith("gwk_").hasSize(47);
        assertThat(ApiKeys.looksValid(key)).isTrue();
        assertThat(ApiKeys.displayPrefix(key)).hasSize(12).isEqualTo(key.substring(0, 12));
    }

    @Test
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            keys.add(ApiKeys.generate());
        }
        assertThat(keys).hasSize(1_000);
    }

    @Test
    void hashIsDeterministicHexSha256() {
        String key = ApiKeys.generate();

        assertThat(ApiKeys.hash(key)).isEqualTo(ApiKeys.hash(key)).hasSize(64).matches("[0-9a-f]+");
        assertThat(ApiKeys.hash(key)).isNotEqualTo(ApiKeys.hash(ApiKeys.generate()));
    }

    @Test
    void malformedKeysAreRejectedWithoutALookup() {
        assertThat(ApiKeys.looksValid(null)).isFalse();
        assertThat(ApiKeys.looksValid("")).isFalse();
        assertThat(ApiKeys.looksValid("sk_live_123")).isFalse();
        assertThat(ApiKeys.looksValid("gwk_tooShort")).isFalse();
    }
}
