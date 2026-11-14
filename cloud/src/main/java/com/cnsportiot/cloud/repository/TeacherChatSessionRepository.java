package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.TeacherChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 教师分析会话仓储 */
public interface TeacherChatSessionRepository extends JpaRepository<TeacherChatSession, UUID> {

    Page<TeacherChatSession> findByTeacherIdAndDeletedFalse(UUID teacherId, Pageable pageable);
}
