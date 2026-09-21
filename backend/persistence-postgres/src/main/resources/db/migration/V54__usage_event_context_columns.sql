-- ============================================================================
-- V54: CAA（Context Attribution Architecture，Spec v1.1 §7.1）——
--      usage_event 增记请求级归属的"声明 vs 服务端裁定"与观测元数据。
--
-- 全列可空、纯增量；既有写入路径不受影响（未提供上下文的请求保持 NULL）。
-- claimed_* 是 Agent/后缀的"声明"（未验证输入）；project_id（既有列）与
-- resolution_status 是服务端裁定结果。session_id 纯观测，永不参与路由/授权。
-- ============================================================================

ALTER TABLE usage_event
    ADD COLUMN session_id         varchar(64),
    ADD COLUMN activity_id        uuid,
    ADD COLUMN claimed_project_id uuid,
    ADD COLUMN resolution_status  varchar(32),
    ADD COLUMN claim_source       varchar(32),
    ADD COLUMN claim_confidence   varchar(16);

COMMENT ON COLUMN usage_event.session_id IS
    '客户端会话标识（如 X-Claude-Code-Session-Id）；观测元数据，可空，不参与路由或授权。';
COMMENT ON COLUMN usage_event.activity_id IS
    '本地 Context Agent 的活动段标识（uuid）；同一活动段内稳定。';
COMMENT ON COLUMN usage_event.claimed_project_id IS
    '客户端"声明"的项目（未验证输入；与 project_id 的服务端裁定分离）。';
COMMENT ON COLUMN usage_event.resolution_status IS
    '服务端裁定：RESOLVED_HEADER | RESOLVED_SUFFIX | SOLE_BINDING | POLICY_ROUTED | UNATTRIBUTED | AMBIGUOUS。';
COMMENT ON COLUMN usage_event.claim_source IS
    '声明来源：prompt_url | tool_path | bash_cwd | system_cwd | suffix | none（声明，非事实）。';
COMMENT ON COLUMN usage_event.claim_confidence IS
    '声明置信度：HIGH | MEDIUM | LOW | NONE（声明，非事实）。';
