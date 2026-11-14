package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/** L2 窗口聚合:某学生某类动作在整节课的统计,供对话/报告直接读。 */
@Entity
@Table(name = "session_aggregate",
       uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "student_id", "action_type"}))
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SessionAggregate extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "action_type", nullable = false, length = 24)
    private String actionType;

    /** 次数、各角均值、通过率、趋势、命中率 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> stats;
}
