# ADR-0019：配额硬阻断（超限拒绝）— 草案

- 状态：**Proposed（2026-09-16）**——**尚未实现**；本草案用于反转锁定决策前的 owner 拍板。任何实现以本 ADR 转 Accepted + owner 明确同意为前提（CLAUDE.md §2：「改变上述决策前必须新增 ADR，并获得用户明确同意」）。
- 实现进度注记（2026-09-16，#684 块①）：草案中**不含数据面准入判定**的那部分已按分块计划先行落地——`quota_rules.enforcement`（V59，落地枚举名 `ALERT`/`REJECT`，即下表方案 B 的 `ALERT_ONLY`/`REJECT`；DB 默认 `ALERT`，存量行为不变）、`quota_enforcement` 阻断投影表、控制面定时评估器、网关路由快照的 blocked-scope 装载。**网关热路径的实际拒绝（429 信封）与前端配置界面尚未实现**，见 #684 块②③——因此本批落地**对运行时行为零影响**：`RouteSnapshot` 的 blocked-scope 投影目前没有任何生产消费点，网关不会因它拒绝或改动任何请求。本注记不改变本 ADR 的状态与结论：仍为 Proposed，待 owner 拍板；块②的准入判定必须在 ADR 转 Accepted 且 owner 明确同意后才能实现。
- 日期：2026-09-16
- 关联：[CLAUDE.md](../../CLAUDE.md) §2「不限流、不因预算阻断，只做 Webhook 告警」；[feature-backlog F51](../feature-backlog.md)（配额硬阻断，状态 ADR）；[api-contract §5.19](../api-contract.md)（配额规则，alerting-only）；#683（COST/YEARLY/NEAR_LIMIT 交付）；腾讯 AI 网关「配额管理：超限处理=拒绝请求」（owner 2026-09-16 样本）；[ADR-0005](0005-no-redis-v1.md)（不引 Redis）；[ADR-0002](0002-transparent-proxy.md)（透明代理红线——本议题不触碰请求内容，仅准入判定）

## 现状

- 配额规则（V23/V58）为 **alerting-only**：水位读时计算、`QUOTA_THRESHOLD` Webhook 告警、`NORMAL/WARNING/NEAR_LIMIT/EXCEEDED` 四档展示；**永不阻断**（api-contract §5.19 明示「硬阻断需 ADR」）。
- 数据面（WebFlux 网关）热路径全内存快照、零阻塞 IO（P95 首包 ≤30ms 目标）；无 Redis；用量事件经有界队列异步落库——**数据面没有任何实时计数器**。
- 「不因预算阻断」的原始语境：单租户私有化、50 账号规模，阻断被判定为收益不足且易伤可用性。owner 样本（腾讯）提供「超限处理=拒绝请求」，使反转具备立项依据，但**决策权在 owner**。

## 决策点 1：是否提供「超限拒绝」

| 选项 | 说明 | 评估 |
|---|---|---|
| A. 维持 alerting-only | 不改任何行为 | 与 owner 样本差异保留；内部治理或有「必须卡住部门预算」的强需求时无法满足 |
| B. **规则级 opt-in 拒绝**（推荐选项形态） | 每条配额规则增 `enforcement ∈ {ALERT_ONLY(默认), REJECT}`；仅 REJECT 规则在准入处拒绝；全局默认不变 | 默认行为=现状（零回归）；按需开启、逐规则可审计；反转范围最小 |
| C. 全局开关 + 规则继承 | 租户级总开关控制默认 enforcement | 多一层继承语义，50 账号规模下收益不足 |

推荐 **B**（若 owner 决定立项）。

## 决策点 2（仅当选 B）：准入判定与计数来源

- **挂点**：`VirtualKeyResolver.resolve()` 之后、上游转发之前（`ProxyController` 入口）——请求上下文已解析出 user/project，与 COST/TOKENS/REQUESTS 的 scope 对齐；MCP 面（消费者维度）不在首版范围。
- **计数来源**（无 Redis 约束下的近似方案）：
  1. 控制面每 **5 分钟**（可配）把各规则当期窗口用量聚合为 `quota_counters(rule_id, period_start, used, synced_at)`；
  2. 快照新增配额段（规则 + counter + periodStart/window 边界），随既有快照通道（NOTIFY + 30s 轮询）下发；
  3. 数据面持 `usedSinceLoad` 内存原子计数（按 ruleId 累加已放行请求的 token/次数；COST 按放行请求的价格快照估算累加）；
  4. 判定：`used_snapshot + usedSinceLoad ≥ limit` → 拒绝。
  - **近似语义（必须文档化）**：误差 = 聚合周期内的在途用量 + 并发瞬时值；额度可能被超出 0–5 分钟窗口内的量。腾讯口径同为「配额使用情况每小时更新」——近似阻断是本类系统的常态，不承诺精确。
- **拒绝语义**：HTTP **429** + 错误信封 `code=QUOTA_EXCEEDED`（含 scope/metric/period/limit/resetAt，`Retry-After` = 窗口结束）；不计费、不触上游、写访问日志与 `mcp_access_log` 无关；触发 `QUOTA_REJECTED` 类型告警（复用 alert_rules 框架，去重按窗口）。
- **与告警的关系**：REJECT 规则仍保留全部预警状态与 Webhook；拒绝是「最后一档」。
- **红线核对**：不改写请求内容、不跨供应商、不做负载选择——准入阻断不属于 ADR-0002 覆盖的「请求/响应改写」；与 F47（QPM/限流）不同：这是**额度治理**不是速率治理（无令牌桶、无 QPS 语义）。

## 后果（若转为 Accepted 并实现）

- `quota_rules` 增 `enforcement` 列（V-next）；api-contract §5.19 更新（含近似语义与 429 信封）；CLAUDE.md §2 措辞需 owner 同意后修订（「只做 Webhook 告警」→「默认只告警；REJECT 规则可超限拒绝」）。
- 控制面新增聚合任务与 `quota_counters` 表；数据面快照结构扩展（向后兼容：无配额段=不判定）。
- 测试面：控制面聚合单测/IT；数据面准入单测（边界/近似语义/并发）+ 快照回环；50 并发 p95 目标复核。
- 风险：近似窗口内超额（见上）；快照滞后导致「已拒绝但页面水位未满」的观感差异（页面必须同标 synced_at）。

## 未决问题（留给 owner 拍板）

1. 是否立项（反转「不因预算阻断」）？
2. 若立项：首版范围是否仅 USER+PROJECT × TOKENS（COST/REQUESTS 后置）？
3. 拒绝信封状态码 429 vs 403（429=窗口后可重试，推荐；403=需管理员操作）。
