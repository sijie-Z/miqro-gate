-- ============================================================================
-- V75: 服务级 MCP 授权名单不得有重复消费者（issue #1339）。
--
-- 机制：V25 给 mcp_access_grants 建了 UNIQUE (service_access_id, tool_id,
-- consumer_id)，但这个约束在**服务级名单上完全不生效**——服务级行的 tool_id 是
-- NULL（NULL=服务级名单，非 NULL=工具覆盖），而 PostgreSQL 的 UNIQUE 认为
-- NULL 之间互不相等。约束被当成去重手段依赖，实际去重能力为零。
-- AdminMcpAccessService.replaceGrants 逐个校验 consumerIds 后直接建行，从不去重，
-- 于是同一个消费者在服务级名单里能落任意多行。
--
-- 实测（本地隔离环境：真 PG 17，同一事务内 A/B 对照）
--   A. tool_id 非空、同一 (service_access, tool, consumer) 插两次
--      ERROR: duplicate key value violates unique constraint "uq_mcp_access_grant"
--   B. 同一 (service_access, consumer) 插两次 tool_id = NULL
--      rows_with_null_tool = 2      ← 两行都进去了
--   C. 控制台列表查询（一行一个条目）
--      00000000-0000-0000-0000-00000000a003
--      00000000-0000-0000-0000-00000000a003
-- 同一个请求体是否被拒，只取决于有没有传 toolId：带 toolId 得 409 RESOURCE_CONFLICT，
-- 不带得 200。审计里记的 consumers 数是**请求项数**而非落库行数
-- （AdminMcpAccessService 的 validated.size()），于是 ["A","A","B"] 记 3、实落 2 行，
-- 审计记录不可复盘。
--
-- 影响边界（已实测，不夸大）：网关侧鉴权不受影响——JdbcRouteSnapshotLoader 把两个
-- 分支都收进 LinkedHashSet，重复行在判定前就被折叠。所以这是脏数据 + 审计失真 +
-- 错误面不一致，不是越权。
--
-- 服务层已同步去重（LinkedHashSet），但那只能挡住"同一请求里重复"。并发写者仍能造
-- 出重复：READ COMMITTED 下两个事务各自 DELETE 看不到对方未提交的 INSERT，各自
-- INSERT 成功后都提交，服务级名单就多出一行（且两行 mode 可能不同）。本索引把
-- "至多一行"从应用层的自觉变成结构保证：并发冲突时后到的写者拿到 23505，
-- 控制面转成可读的 409，而不是静默多落一行。
--
-- 语义边界：**只约束服务级名单**（WHERE tool_id IS NULL）。工具级覆盖本就受
-- uq_mcp_access_grant 保护（那里 tool_id 非空，NULL 语义不适用），且 tool_id 在
-- 引用完整性上必须保持可空，不能改成 NOT NULL。索引是部分的，不改变任何合法数据。
--
-- 上线前置（吸取 #1249）：已经在跑的环境里可能已经存在重复行——本缺陷正是它们被
-- 造出来的原因。直接建唯一索引的话，Flyway 会以
-- "could not create unique index ... is duplicated" 失败并中止整轮迁移，控制面
-- 卡死在启动且迁移表不留记录。所以先把重复行收敛到一行，再建索引。
--
-- 删除是安全的：
--   * 被删行与保留行同 (tenant_id, service_access_id, tool_id IS NULL, consumer_id)，
--     是同一授权的冗余副本，不携带任何额外信息；网关鉴权结果逐位相同（快照去重）。
--   * 审计不依赖本表——每次 PUT 各自那条 MCP_ACCESS_GRANTS 都原样留在
--     admin_audit_events（append-only，本迁移一行都不碰）。
--   * 保留 (created_at, id) 最大的一条，即最近一次写入的结果。
--
--   诚实说明：并发场景下两行的 created_at（= 事务开始时间 now()）不保证与提交顺序
--   一致，所以"保留最新"只保证**确定性**，不声称它一定是 commit 顺序上的最后写者。
--   无论保留哪一条，落到快照里的授权效果都相同；若两行 mode 不同（并发 ALLOW/DENY），
--   本迁移无法回溯真实意图，需 operator 事后核对并重设一次。
--
-- 核对现状（可选，迁移前后都可跑；迁移后必须返回空集）：
--   SELECT service_access_id, consumer_id, count(*), array_agg(DISTINCT mode)
--     FROM mcp_access_grants WHERE tool_id IS NULL
--    GROUP BY 1, 2 HAVING count(*) > 1;
-- ============================================================================

-- 每个 (service_access_id, consumer_id) 只保留 (created_at, id) 最大的一行。
-- (id) 是主键，(created_at, id) 全序，故每组恰好留下一条。
DELETE FROM mcp_access_grants a
 USING mcp_access_grants b
 WHERE a.tool_id IS NULL
   AND b.tool_id IS NULL
   AND a.service_access_id = b.service_access_id
   AND a.consumer_id = b.consumer_id
   AND (a.created_at, a.id) < (b.created_at, b.id);

CREATE UNIQUE INDEX uq_mcp_access_grant_server_list ON mcp_access_grants (service_access_id, consumer_id)
    WHERE tool_id IS NULL;
