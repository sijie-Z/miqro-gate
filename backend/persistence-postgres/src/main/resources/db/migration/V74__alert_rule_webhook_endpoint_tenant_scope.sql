-- #1335: alert_rules.webhook_endpoint_id 是单列外键，没有租户维度 —— 一个租户的告警
-- 规则可以指向另一个租户的 Webhook 端点，而两侧都看不见这种状态：
--   * WebhookEndpointService.delete 的依赖检查带 tenant_id 过滤：别的租户的规则不在
--     它的视野里，于是端点被删时 ON DELETE SET NULL 把那条规则静默脱钩（规则还在、
--     投递目标没了，谁都没收到提示）；
--   * AlertEventDispatcher 投递时按规则自己的 tenant 去取端点，命中跨租户 404，而
--     审批通知型规则（MODEL_APPROVAL_*）是在 @Transactional 里投递的，异常直接回滚
--     审批本身。
-- 单列外键因此升级为 (tenant_id, webhook_endpoint_id) 复合外键：让"跨租户引用"在
-- 数据库层就写不进去，而不是靠每条写入路径各自记得校验。
--
-- 复合外键要求被引用列上有唯一约束（或非部分唯一索引），webhook_endpoints 目前没有，
-- 先补上。(tenant_id, id) 天然唯一（id 已是主键），该约束不改变任何合法数据。
ALTER TABLE webhook_endpoints
    ADD CONSTRAINT uq_webhook_endpoints_tenant_id UNIQUE (tenant_id, id);

-- 存量脏数据：已经跨租户的引用必须清掉，否则下面的 ADD CONSTRAINT 会失败（这正是
-- 我们想要的——不能假装它合法）。语义与 ON DELETE SET NULL 一致：只清
-- webhook_endpoint_id，规则本身与它的 tenant_id 保留；version + 1 让正在编辑这条
-- 规则的并发请求（基于 version 的 CAS）发现自己读到的快照已过期，而不是静默覆盖。
--
-- 这是升级期的数据修改，operator 需要注意：被清掉引用的规则不再投递 Webhook（事件
-- 仍照常记录），需要人工重新指向本租户的端点。受影响行数见下方 UPDATE 的行数。
UPDATE alert_rules ar
   SET webhook_endpoint_id = NULL, version = ar.version + 1, updated_at = now()
  FROM webhook_endpoints we
 WHERE we.id = ar.webhook_endpoint_id
   AND we.tenant_id <> ar.tenant_id;

-- 用普通 ON DELETE SET NULL 会把复合外键的两列一起置空，而 tenant_id 是 NOT NULL
-- ——删除端点会变成 NOT NULL 违例。改用列清单形式（PostgreSQL 15+，本项目下限 16）：
-- 只清 webhook_endpoint_id，规则仍留在原租户下。
ALTER TABLE alert_rules DROP CONSTRAINT alert_rules_webhook_endpoint_id_fkey;
ALTER TABLE alert_rules
    ADD CONSTRAINT fk_alert_rules_webhook_endpoint
        FOREIGN KEY (tenant_id, webhook_endpoint_id)
        REFERENCES webhook_endpoints (tenant_id, id)
        ON DELETE SET NULL (webhook_endpoint_id);
