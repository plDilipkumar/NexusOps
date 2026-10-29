package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoginIdentityHasherTest {
    private static final String KEY = "ci-only-hmac-key-material-0123456789abcdef";

    @Test
    void normalizesIdentityBeforeHashing() {
        LoginIdentityHasher hasher = new LoginIdentityHasher(KEY);

        assertThat(hasher.hash(" Alice@Example.test "))
                .isEqualTo(hasher.hash("alice@example.test"));
    }

    @Test
    void producesFixedLengthLowercaseDigestWithoutEmbeddingIdentity() {
        String email = "person@example.test";
        String digest = new LoginIdentityHasher(KEY).hash(email);

        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(digest).doesNotContain(email);
    }

    @Test
    void differentKeysProduceDifferentDigests() {
        assertThat(new LoginIdentityHasher(KEY).hash("alice@example.test"))
                .isNotEqualTo(new LoginIdentityHasher("different-ci-only-hmac-key-material-012345").hash("alice@example.test"));
    }

    @Test
    void rejectsMissingOrShortKeys() {
        assertThatThrownBy(() -> new LoginIdentityHasher(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginIdentityHasher("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidIdentitiesMapToOneNonPersonalSentinel() {
        LoginIdentityHasher hasher = new LoginIdentityHasher(KEY);

        assertThat(hasher.hash(null)).isEqualTo(hasher.hash("   "));
        assertThat(hasher.hash("x".repeat(255))).isEqualTo(hasher.hash(null));
    }
}
