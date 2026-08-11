package com.cnsportiot.cloud.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Primary
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redis;

    @Override
    public void store(String jti, UUID accountId, Duration ttl) {
        redis.opsForValue().set(KEY_PREFIX + jti, accountId.toString(), ttl);
    }

    @Override
    public boolean isValid(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }

    @Override
    public void revoke(String jti) {
        redis.delete(KEY_PREFIX + jti);
    }
}
