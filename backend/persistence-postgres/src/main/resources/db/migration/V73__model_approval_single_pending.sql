-- ============================================================================
-- V73: 同一 (virtual_key_id, model_id) 至多一条 PENDING 申请（issue #1305）。
--
-- 机制：ModelApprovalService.submit 的重复提交防护是**纯 check-then-act**——先
-- SELECT 该密钥下是否已有同模型的 PENDING 行，再无条件 INSERT（服务层 113-132 行）。
-- 本表从建库至今只有主键，没有任何唯一约束；隔离级别是 PostgreSQL 默认的
-- READ COMMITTED，读到的快照约束不了后续写入。于是并发到达的 N 个相同申请
-- 全部通过检查、全部落库。实测（本地隔离环境：控制面 + 真 PG）6 个 barrier
-- 同步的相同请求 → 6/6 HTTP 201、6 条 PENDING、6 条 MODEL_APPROVAL_SUBMITTED
-- 审计、6 条告警事件（规则配了 webhook 就是 6 次对外投递）。
--
-- 服务自己那句 409 文案是「…请等待管理员处理，无需重复提交」，即代码的显式意图
-- 就是「至多一条」。本索引把这个意图从"一次 SELECT 的运气"变成结构保证：即使
-- 将来出现绕过 service 的写入路径，也造不出第二条待审。
--
-- 语义边界：**只约束 PENDING**。终态行不参与，因此同一 (Key, 模型) 的正常历史
-- ——「申请 → 驳回 → 再次申请」，或多条已处理记录——不受影响，也不会挡住把
-- 模型重新申请一遍。
--
-- 上线前置（吸取 #1249）：已经在跑的环境里可能已经存在重复 PENDING 行——本缺陷
-- 就是它们被造出来的原因。直接建唯一索引的话，Flyway 会以
-- "could not create unique index ... is duplicated" 失败并中止整轮迁移，控制面
-- 卡死在启动且迁移表不留记录，正是 #1249 的形状，不再重演。所以这里先把重复行
-- 收敛到只剩最早的一条，再建索引。删除是安全的，理由有三：
--   * 被删行与保留行同 (tenant, key, model, status)，是同一诉求的冗余副本，
--     不携带任何额外信息（requested_by 一项见下条，是本条的唯一例外）；
--   * 审计不依赖本表——每个副本各自那条 MODEL_APPROVAL_SUBMITTED 都原样留在
--     admin_audit_events（append-only，本迁移一行都不碰），所以追责链完整；
--   * 密钥在常规路径下固定绑定单用户（submit 经 ownedKey 校验），但 ownedKey 对
--     SYSTEM_ADMIN 放行他人名下的密钥（ModelApprovalService#ownedKey 的角色分支），
--     因此同 (Key, 模型) 的 PENDING 可以来自两个不同的人，且被删的可能是管理员
--     那条。这仍是同一诉求的重复提交、审计也各自留档，删除安全；但**不要用本表
--     的行数或行内容反推"谁申请过"**，那是 admin_audit_events 的职责。
--
-- 核对现状（可选，迁移前后都可跑）：
--   SELECT virtual_key_id, model_id, count(*) FROM model_approval
--    WHERE status = 'PENDING' GROUP BY 1, 2 HAVING count(*) > 1;
-- ============================================================================

-- 保留 (created_at, id) 最小的一条，其余重复副本删除；(id) 是主键，(created_at, id)
-- 全序，故每组恰好留下一条。
DELETE FROM model_approval a
 USING model_approval b
 WHERE a.status = 'PENDING'
   AND b.status = 'PENDING'
   AND a.virtual_key_id = b.virtual_key_id
   AND a.model_id = b.model_id
   AND (a.created_at, a.id) > (b.created_at, b.id);

CREATE UNIQUE INDEX uq_model_approval_pending ON model_approval (virtual_key_id, model_id)
    WHERE status = 'PENDING';
