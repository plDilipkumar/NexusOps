package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionTokenCodecTest {
    private final SessionTokenCodec codec = new SessionTokenCodec();

    @Test
    void issuesUniqueCanonicalUrlSafeTokens() {
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 128; i++) {
            String token = codec.issueToken();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            tokens.add(token);
        }

        assertThat(tokens).hasSize(128);
    }

    @Test
    void hashesTokenToLowercaseSha256HexWithoutStoringRawValue() {
        String token = codec.issueToken();

        String first = codec.hashToken(token);
        String second = codec.hashToken(token);

        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(token);
    }

    @Test
    void rejectsNullBlankAndMalformedTokens() {
        assertThatThrownBy(() -> codec.hashToken(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.hashToken(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.hashToken("not-a-session-token"))
                .isInstanceOf(IllegalArgumentException.class);
        // A non-zero unused trailing bit is non-canonical Base64URL for 32 bytes.
        assertThatThrownBy(() -> codec.hashToken("A".repeat(42) + "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
