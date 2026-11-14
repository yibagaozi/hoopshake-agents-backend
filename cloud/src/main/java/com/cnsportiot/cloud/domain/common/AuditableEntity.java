package com.cnsportiot.cloud.domain.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/** 在 BaseEntity 基础上增加 updated_at,供需要跟踪更新时间的实体继承。 */
@Getter
@MappedSuperclass
public abstract class AuditableEntity extends BaseEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
