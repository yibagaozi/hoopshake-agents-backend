package com.cnsportiot.cloud.harness.audit;

import com.cnsportiot.cloud.domain.entity.AuditLog;
import com.cnsportiot.cloud.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** 统一审计写入。审计一律走 {@code REQUIRES_NEW} 独立事务，审计写失败不阻断业务(除越权拦截本身)，吞异常记 error */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 记一条审计。独立事务提交,失败不上抛
     *
     * @param accountId        操作者
     * @param action           {@link AuditAction}
     * @param targetStudentId  数据主体(可空,如知识库操作)
     * @param detail           上下文(jsonb),可空
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID accountId, String action, UUID targetStudentId, Map<String, Object> detail) {
        try {
            auditLogRepository.save(AuditLog.builder()
                    .accountId(accountId)
                    .action(action)
                    .targetStudentId(targetStudentId)
                    .detail(detail)
                    .build());
        } catch (RuntimeException e) {
            // 审计失败不能阻断业务
            log.error("审计写入失败 action={} account={} target={}", action, accountId, targetStudentId, e);
        }
    }
}

