package com.cnsportiot.cloud.repository;

import com.cnsportiot.cloud.domain.entity.SessionAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionAggregateRepository extends JpaRepository<SessionAggregate, UUID> {

    List<SessionAggregate> findBySessionIdAndStudentId(UUID sessionId, UUID studentId);

    boolean existsBySessionIdAndStudentIdAndActionType(UUID sessionId, UUID studentId, String actionType);

    /** 派生入库用的 upsert 查找(唯一键 session+student+action) */
    Optional<SessionAggregate> findBySessionIdAndStudentIdAndActionType(
            UUID sessionId, UUID studentId, String actionType);

    /** 单学生聚合 + 会话生成时间(按时间升序),供走势。row[0]=SessionAggregate, row[1]=OffsetDateTime */
    @Query("SELECT sa, s.generatedAt FROM SessionAggregate sa JOIN TrainingSession s ON s.id = sa.sessionId "
            + "WHERE sa.studentId = :sid ORDER BY s.generatedAt ASC")
    List<Object[]> findWithTimeByStudent(@Param("sid") UUID sid);

    /** 一组学生聚合 + 会话生成时间(升序),供群体聚合/取每人最近值 */
    @Query("SELECT sa, s.generatedAt FROM SessionAggregate sa JOIN TrainingSession s ON s.id = sa.sessionId "
            + "WHERE sa.studentId IN :ids ORDER BY s.generatedAt ASC")
    List<Object[]> findWithTimeByStudentIn(@Param("ids") Collection<UUID> ids);
}
