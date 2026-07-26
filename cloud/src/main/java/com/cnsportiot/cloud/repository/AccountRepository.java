package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 账号仓储。登录标识三选一(username / email / phone)在 AppService 按序匹配 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByUsername(String username);

    Optional<Account> findByEmail(String email);

    Optional<Account> findByPhone(String phone);

    boolean existsByUsername(String username);

    boolean existsByStaffNo(String staffNo);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);
}
