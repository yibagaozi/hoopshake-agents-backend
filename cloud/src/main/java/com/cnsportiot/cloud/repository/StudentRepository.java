package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 学生档案仓储 */
public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByAccountId(UUID accountId);

    Optional<Student> findByStudentNo(String studentNo);

    /** 按学号查已存在的学生 */
    interface StudentRef {
        UUID getStudentId();
        String getStudentNo();
        String getDisplayName();
    }

    @Query("""
            SELECT s.id          AS studentId,
                   s.studentNo   AS studentNo,
                   a.displayName AS displayName
            FROM Student s
                 JOIN Account a ON a.id = s.accountId
            WHERE s.studentNo IN :studentNos
            """)
    List<StudentRef> findRefsByStudentNoIn(@Param("studentNos") Collection<String> studentNos);
}
