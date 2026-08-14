package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.AuditableEntity;
import com.cnsportiot.cloud.domain.enums.KnowledgeDocStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

/**
 * 知识源文档目录(catalog,见 §8.0 方案 A)。
 *
 * <p>chunk 正文 + 向量 + metadata 存在 Spring AI 自管的向量表里,本表<b>只记源文档账</b>:
 * 便于重导、下架、reindex、幂等判定。删除时用 {@link #chunkIds} 精确删除向量表里的 chunk。
 */
@Entity
@Table(name = "knowledge_document",
        uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_doc_docid", columnNames = "doc_id"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class KnowledgeDocument extends AuditableEntity {

    /** 稳定业务标识(幂等键) */
    @Column(name = "doc_id", nullable = false, length = 128)
    private String docId;

    /** 来源:textbook / team */
    @Column(name = "source", length = 32)
    private String source;

    /** 域:如 shooting */
    @Column(name = "domain", length = 64)
    private String domain;

    /** checkpoint 对齐(当前留空,算法侧就位后 reindex 回填) */
    @Column(name = "checkpoint_id", length = 64)
    private String checkpointId;

    /** 版本,重导自增 */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private KnowledgeDocStatus status = KnowledgeDocStatus.PROCESSING;

    /** 内容哈希,未变化则整篇跳过 */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** 源 Markdown 正文(权威副本,供 reindex/回填重切,无需重新上传) */
    @Column(name = "content", columnDefinition = "text")
    private String content;

    /** 切分出的 chunk 数 */
    @Column(name = "chunk_count")
    @Builder.Default
    private int chunkCount = 0;

    /** 灌入向量表的 chunk id 列表,删除/重导时精确清除 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chunk_ids", columnDefinition = "jsonb")
    private List<String> chunkIds;

    /** 切分参数快照(供 reindex 复现) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "split_params", columnDefinition = "jsonb")
    private Map<String, Object> splitParams;

    /** 失败原因(status=FAILED) */
    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 是否已下架 */
    @Column(name = "removed", nullable = false)
    @Builder.Default
    private boolean removed = false;
}
