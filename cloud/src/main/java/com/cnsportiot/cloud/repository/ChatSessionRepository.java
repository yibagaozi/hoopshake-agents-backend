package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    /** 会话列表:本人、未软删,按 updated_at 倒序(分页由 Pageable 提供排序)。 */
    Page<ChatSession> findByStudentIdAndDeletedFalse(UUID studentId, Pageable pageable);

    /** 归属校验用:按 id + 学生 + 未删。 */
    Optional<ChatSession> findByIdAndStudentIdAndDeletedFalse(UUID id, UUID studentId);
}
