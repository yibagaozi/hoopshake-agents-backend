package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.ChatMessage;
import com.cnsportiot.cloud.domain.enums.MessageRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    /** 会话消息,正序分页(3.3)。 */
    Page<ChatMessage> findByChatSessionIdOrderByCreatedAtAsc(UUID chatSessionId, Pageable pageable);

    /** 取最近若干轮(倒序取,调用方再翻转)组上下文窗口。 */
    List<ChatMessage> findByChatSessionIdOrderByCreatedAtDesc(UUID chatSessionId, Pageable pageable);

    /** 统计会话内某角色的消息数(协助触发:数学生提问轮次) */
    long countByChatSessionIdAndRole(UUID chatSessionId, MessageRole role);
}
