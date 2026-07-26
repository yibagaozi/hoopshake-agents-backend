package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.Teacher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 教师档案仓储 */
public interface TeacherRepository extends JpaRepository<Teacher, UUID> {

    Optional<Teacher> findByAccountId(UUID accountId);
}
