-- HOOPSHAKE 云端 Agent 里程碑一(基本聊天 + RAG + 审计)建表脚本
-- 本项目 schema 外部管理(未开 ddl-auto),按需在目标库执行。全部幂等。
-- 依赖:pgvector 扩展(RAG 向量表由 Spring AI 在 initialize-schema=true 时自建,见文末)。

-- ① 对话会话(实体 ChatSession)
CREATE TABLE IF NOT EXISTS chat_session (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    student_id  UUID NOT NULL,
    lesson_id   UUID,
    agent_type  VARCHAR(32) NOT NULL DEFAULT 'skill_coach',
    title       VARCHAR(64),
    deleted     BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_chat_session_student ON chat_session (student_id, updated_at);

-- ② 对话消息(实体 ChatMessage)
CREATE TABLE IF NOT EXISTS chat_message (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL,
    chat_session_id UUID NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    token_usage     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message (chat_session_id, created_at);

-- ③ 知识源文档目录(实体 KnowledgeDocument;chunk 正文/向量在 Spring AI 向量表)
CREATE TABLE IF NOT EXISTS knowledge_document (
    id            UUID PRIMARY KEY,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    doc_id        VARCHAR(128) NOT NULL,
    source        VARCHAR(32),
    domain        VARCHAR(64),
    checkpoint_id VARCHAR(64),
    version       INTEGER NOT NULL DEFAULT 1,
    status        VARCHAR(16) NOT NULL,
    content_hash  VARCHAR(64),
    content       TEXT,
    chunk_count   INTEGER NOT NULL DEFAULT 0,
    chunk_ids     JSONB,
    split_params  JSONB,
    error_message VARCHAR(512),
    removed       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_knowledge_doc_docid UNIQUE (doc_id)
);

-- ④ audit_log:已存在(建档审计),本里程碑复用同表写 KNOWLEDGE_* / CHAT_ASK,无需变更。

-- ⑤ RAG 向量表:由 Spring AI PgVectorStore 管理。
--    首次将 spring.ai.vectorstore.pgvector.initialize-schema 设为 true 自动建表(默认表名 vector_store)。
--    需先安装扩展:  CREATE EXTENSION IF NOT EXISTS vector;
