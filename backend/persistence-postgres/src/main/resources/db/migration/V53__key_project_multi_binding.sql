-- ============================================================================
-- V53: 单密钥多项目（ADR-0018）——key_project_binding 每行携带自己的授权。
--
-- 背景：架构设计报告 §3 的"单密钥多项目"要求一把 Key 可绑多个项目；表天然
-- 支持多行（唯一约束为 (virtual_key_id, project_id)），但装载器只取一行、且
-- 授权经 virtual_keys.grant_id 反查——多项目必然落空。本迁移：
--   ① 为每个绑定行增加 grant_id（回填自 virtual_keys.grant_id，存量单绑定
--      语义与路由结果逐字节不变）；
--   ② 安全清理：project/tenant 与 grant 不匹配的历史绑定（装载器本就从不
--      路由它们）置为 DISABLED，为下一步的复合外键铺平数据；
--   ③ 复合外键 (grant_id, project_id, tenant_id) 在数据库层保证"绑定与其
--      授权属于同一项目、同一租户"——不再只靠装载器 JOIN 兜底；
--   ④ 回填缺失的项目标签（'proj-' || uuid 前缀；与详细设计 V4 原文
--      `project_tag = 'proj-' || id` 的意图一致）。
-- 不修改任何既有迁移。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 绑定行自己的授权：解析到哪一行，凭证/产品/模型范围就取哪一行
-- ---------------------------------------------------------------------------
ALTER TABLE key_project_binding ADD COLUMN grant_id uuid;

UPDATE key_project_binding b
SET grant_id = vk.grant_id
FROM virtual_keys vk
WHERE vk.id = b.virtual_key_id;

-- 2. 安全清理：任何项目/租户与授权不匹配的历史绑定（历史代码路径不可能产生，
--    此处兜底保证外键可加；这类行在旧装载器里也从不参与路由）。
UPDATE key_project_binding b
SET status = 'DISABLED', version = version + 1, updated_at = now()
WHERE NOT EXISTS (
    SELECT 1 FROM project_provider_grants g
    WHERE g.id = b.grant_id AND g.project_id = b.project_id AND g.tenant_id = b.tenant_id
);

ALTER TABLE key_project_binding ALTER COLUMN grant_id SET NOT NULL;

-- 3. 复合外键：数据库层保证绑定 × 授权的项目/租户一致（评审硬化项）。
--    引用锚点为 grants 上的 (id, project_id, tenant_id) 唯一约束。
ALTER TABLE project_provider_grants
    ADD CONSTRAINT uq_ppg_id_project_tenant UNIQUE (id, project_id, tenant_id);

ALTER TABLE key_project_binding
    ADD CONSTRAINT fk_kpb_grant_project_tenant
        FOREIGN KEY (grant_id, project_id, tenant_id)
        REFERENCES project_provider_grants (id, project_id, tenant_id) ON DELETE RESTRICT;

CREATE INDEX idx_key_project_binding_grant_id ON key_project_binding (grant_id);

COMMENT ON TABLE key_project_binding IS
    'Virtual Key × 项目绑定（ADR-0018：一把 Key 可绑多个项目）。每行携带自己的 grant_id；'
    '网关按 (密钥, 标签) 命中一行后，以该行的 grant 解析凭证/产品/授权模型。'
    '复合外键 (grant_id, project_id, tenant_id) 在数据库层保证绑定与授权的项目/租户一致。';
COMMENT ON COLUMN key_project_binding.grant_id IS
    '该绑定的授权（凭证+产品+模型范围的来源）；迁移回填自 virtual_keys.grant_id。';

-- ---------------------------------------------------------------------------
-- 4. 项目标签回填：uuid 前 12 位（去连字符）。50 账号规模下唯一性充分；
--    与 (tenant_id, project_tag) 部分唯一索引冲突时迁移将显式失败（可人工
--    加长），不会静默产生歧义。
-- ---------------------------------------------------------------------------
UPDATE projects
SET project_tag = 'proj-' || left(replace(id::text, '-', ''), 12)
WHERE project_tag IS NULL;
