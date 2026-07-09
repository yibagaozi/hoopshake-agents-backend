package com.cnsportiot.cloud.domain.entity;

import com.cnsportiot.cloud.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 检查点知识库(RAG)。embedding 是 pgvector 的 vector(1024) 类型(智谱 embedding-3)。
 *
 * 说明:JPA/Hibernate 无原生 vector 类型支持。实践中二选一:
 *   (a) 用 float[] + 自定义 UserType 映射到 vector 列(需引入 pgvector-java 或自写 Type);
 *   (b) 向量的写入与相似度检索走原生 SQL / Spring AI 的 VectorStore,不经 JPA 管理该列。
 * 推荐 (b):RAG 检索本就用 Spring AI PgVectorStore,embedding 字段不映射进实体,
 *   实体只管元数据;检索由 VectorStore 负责。故此处 embedding 标 @Transient 占位说明。
 */
@Entity
@Table(name = "checkpoint_knowledge")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CheckpointKnowledge extends BaseEntity {

    @Column(name = "checkpoint_id", nullable = false, length = 64)
    private String checkpointId;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** embedding vector(1024) 不由 JPA 管理,由 Spring AI VectorStore 读写 */
    @Transient
    private float[] embedding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
