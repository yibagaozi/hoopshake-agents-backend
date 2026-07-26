package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 学生档案仓储 */
public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByAccountId(UUID accountId);
}
