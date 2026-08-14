package com.cnsportiot.cloud.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRefreshTokenStoreTest {

    private InMemoryRefreshTokenStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRefreshTokenStore();
    }

    @Test
    void storeAndValidate() {
        store.store("jti-1", UUID.randomUUID(), Duration.ofMinutes(5));
        assertThat(store.isValid("jti-1")).isTrue();
    }

    @Test
    void unknownJti_returnsFalse() {
        assertThat(store.isValid("nonexistent")).isFalse();
    }

    @Test
    void revoke_invalidatesToken() {
        store.store("jti-2", UUID.randomUUID(), Duration.ofMinutes(5));
        store.revoke("jti-2");
        assertThat(store.isValid("jti-2")).isFalse();
    }

    @Test
    void revoke_nonexistent_noException() {
        store.revoke("nonexistent");
    }

    @Test
    void expiredToken_returnsFalse() {
        store.store("jti-3", UUID.randomUUID(), Duration.ofMillis(-1));
        assertThat(store.isValid("jti-3")).isFalse();
    }
}

