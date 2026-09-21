-- ============================================================================
-- V56: CAA Project Registry（Spec v1.1 §7.4）—— project_repositories。
--
-- repo_key = 规范化 github.com/{owner}/{repo}（小写）；唯一 (tenant_id, repo_key)。
-- Agent 经网关 GET /v1/context-registry（虚拟 Key 认证）拉取"该 Key 绑定项目"
-- 的映射，用于把本地证据（git remote / PR URL）解析为 project_id。
-- ============================================================================

CREATE TABLE project_repositories (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id  uuid         NOT NULL,
    repo_key    varchar(200) NOT NULL,
    created_by  uuid,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_project_repositories_project
        FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_project_repositories_tenant_repo UNIQUE (tenant_id, repo_key)
);

CREATE INDEX idx_project_repositories_project ON project_repositories (project_id);

COMMENT ON TABLE project_repositories IS
    'CAA Project Registry（Spec v1.1 §7.4）：仓库→项目映射；repo_key 规范化为小写 github.com/{owner}/{repo}。';
