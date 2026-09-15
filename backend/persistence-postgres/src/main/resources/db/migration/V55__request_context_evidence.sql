-- ============================================================================
-- V55: CAA（Spec v1.1 §7.2）—— request_context_evidence：归属决策的证据审计表。
--
-- 记录"为什么这笔请求被判定为某项目"的证据（仅元数据：来源/规范化值/置信度/
-- 作用域，绝不含消息正文）。网关在 Context 解析时写入；供审计与事后重分类。
-- ============================================================================

CREATE TABLE request_context_evidence (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    request_id  varchar(64)  NOT NULL,
    source      varchar(32)  NOT NULL,
    value       varchar(512) NOT NULL,
    confidence  varchar(16)  NOT NULL,
    scope       varchar(16)  NOT NULL DEFAULT 'turn'
                CHECK (scope IN ('turn', 'session')),
    observed_at timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_request_context_evidence_request ON request_context_evidence (tenant_id, request_id);
CREATE INDEX idx_request_context_evidence_observed ON request_context_evidence (observed_at DESC);

COMMENT ON TABLE request_context_evidence IS
    'CAA 归属证据（Spec v1.1 §7.2）：source ∈ prompt_url|tool_path|bash_cwd|system_cwd|git_remote|header|suffix；'
    'value 为规范化证据值（repo key/绝对路径/tag）；仅元数据，无正文。';
