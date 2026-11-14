package com.cnsportiot.cloud.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * refresh token 存储的内存 Mock
 * 上线替换为 Redis 实现:{@code SET refresh:{jti} {accountId} EX {ttl}} / {@code EXISTS} / {@code DEL}
 */
@Component
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private record Entry(UUID accountId, Instant expiresAt) {}

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    @Override
    public void store(String jti, UUID accountId, Duration ttl) {
        tokens.put(jti, new Entry(accountId, Instant.now().plus(ttl)));
    }

    @Override
    public boolean isValid(String jti) {
        Entry e = tokens.get(jti);
        if (e == null) {
            return false;
        }
        if (Instant.now().isAfter(e.expiresAt())) {
            tokens.remove(jti);
            return false;
        }
        return true;
    }

    @Override
    public void revoke(String jti) {
        tokens.remove(jti);
    }
}

