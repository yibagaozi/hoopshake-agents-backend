package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 动作片段索引(对齐算法侧 clips)。一条 = 一次完整动作,存边界/摘要/评分,
 * 不存逐帧;motion_uri 指向 MinIO 的逐帧数据,供 3D 回放按需拉取。
 */
@Entity
@Table(name = "action_clip",
       uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "student_id", "clip_index"}))
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ActionClip extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "clip_index", nullable = false)
    private Integer clipIndex;   // 该学生本 session 第几次动作

    @Column(name = "action_type", nullable = false, length = 24)
    private String actionType;   // free_throw/jump_shot/layup/...

    @Column(name = "start_ms", nullable = false, precision = 12, scale = 1)
    private BigDecimal startMs;

    @Column(name = "end_ms", nullable = false, precision = 12, scale = 1)
    private BigDecimal endMs;

    @Column(name = "release_ms", precision = 12, scale = 1)
    private BigDecimal releaseMs;

    @Column(name = "anchor_camera", length = 16)
    private String anchorCamera;

    @Column(name = "zone_id", length = 32)
    private String zoneId;

    /** 阶段边界 [{name,start_ms,end_ms}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> phases;

    /** 进球结果 */
    @Column(name = "shot_made")
    private Boolean shotMade;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> score;

    /** 逐帧数据文件位置(MinIO),3D 回放按需拉取 */
    @Column(name = "motion_uri", length = 512)
    private String motionUri;

    /** 在文件中的行/帧范围 {start,end} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "motion_range", columnDefinition = "jsonb")
    private Map<String, Object> motionRange;
}
