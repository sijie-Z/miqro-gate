-- ============================================================================
-- V57: CAA 未归属策略（Spec v1.1 §7.3，实施 issue #647）。
--
-- 多绑定 Key 且请求级上下文无法解析时：租户未配置策略 → 400 CONTEXT_REQUIRED
-- （基线，不变）；已配置 → 以策略的（凭证/产品/模型范围）路由，usage 记账到
-- 未归属桶项目（projects.system = true，不可被建 Key 选择）。
--
-- 不变量：归属未知永不借用具体项目的 grant/凭证；策略凭证与项目资源分离
-- （管理面在凭证被 grant 引用时给出告警，见 issue #647 开放问题 Q1）。
-- ============================================================================

ALTER TABLE projects ADD COLUMN system boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN projects.system IS
    '系统项目（如未归属桶 UNATTRIBUTED）：不可被建 Key 选择；常规项目选择器应过滤。';

CREATE TABLE unattributed_policy (
    tenant_id           uuid         PRIMARY KEY REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id          uuid         NOT NULL,
    credential_id       uuid         NOT NULL,
    provider_product_id uuid         NOT NULL,
    model_scope         jsonb        NOT NULL DEFAULT '[]'::jsonb,
    updated_by          uuid,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_uap_project
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_uap_credential
        FOREIGN KEY (tenant_id, credential_id) REFERENCES upstream_credentials (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_uap_product
        FOREIGN KEY (provider_product_id) REFERENCES provider_products (id) ON DELETE RESTRICT
);

COMMENT ON TABLE unattributed_policy IS
    'CAA 未归属策略（Spec v1.1 §7.3，每租户至多一行）：无法归属的请求以该策略的凭证/产品/模型范围路由，'
    '记账到 project_id 指向的未归属桶项目；model_scope 为空数组 = 产品上游目录全部 ACTIVE 模型。';
