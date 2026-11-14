package com.cnsportiot.cloud.auth;

import java.time.Duration;
import java.util.UUID;

/**
 * refresh token 存储端口(可撤销 + 轮换)
 * 一期用内存 Mock 实现({@code InMemoryRefreshTokenStore}),上线替换为 Redis 适配器
 */
public interface RefreshTokenStore {

    /** 登录/刷新时,以 jti 记录一枚有效 refresh token */
    void store(String jti, UUID accountId, Duration ttl);

    /** 该 jti 是否仍有效 */
    boolean isValid(String jti);

    /** 吊销token */
    void revoke(String jti);
}
