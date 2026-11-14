package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/** 工具调用审计。target_student_id 使越权访问一查即现。 */
@Entity
@Table(name = "audit_log")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AuditLog extends BaseEntity {

    @Column(name = "account_id")
    private UUID accountId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "target_student_id")
    private UUID targetStudentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> detail;
}
