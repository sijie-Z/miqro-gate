# 用量、成本与导出

## 1. 设计目标

- 每个请求都能追溯到用户、项目、Virtual Key、供应商产品、Plan 和真实凭证。
- 支持按量 API 与个人/团队/企业 Plan 的不同计费语义。
- 管理员可导出足够详细的原始记录，与供应商官方明细人工比对。
- 不保存提示词、代码、工具参数或模型回答正文。

## 2. 请求状态

**G2.4 已实现**（`request_usage_records.request_status`，生命周期记录终态）：

- `SUCCEEDED`（上游 2xx，已写完）
- `UPSTREAM_REJECTED`（上游非 2xx）
- `UPSTREAM_UNAVAILABLE`（连接失败/不可达，未出首字节）
- `CLIENT_CANCELLED`（客户端断开，优先于任何已观测状态码）
- `TIMEOUT_BEFORE_FIRST_BYTE`（超时且未出首字节）
- `STREAM_INTERRUPTED`（超时或上游错误且已出首字节）

记录在请求到达上游时以 `IN_FLIGHT` 打开，终态只 finalize 一次（guarded upsert）。

**建议但首版刻意不落库**：

- `AUTH_REJECTED` / `MODEL_NOT_ALLOWED`——鉴权与模型预校验失败不打开生命周期记录（不达上游无计费语义），由访问日志与安全事件覆盖。
- `USAGE_PARSE_FAILED`——由 `usage_missing=true` 显式标记取代（SUCCEEDED 但上游未返回 usage），比独立状态更利于统计。

鉴权失败请求也记录安全事件，但不生成可计费 UsageEvent，除非请求已经到达上游。

## 3. Token 统一字段

- `input_tokens`
- `output_tokens`
- `cache_read_tokens`
- `cache_write_tokens`
- `reasoning_tokens`
- `total_tokens`
- `provider_reported_total_tokens`

不能由一个协议可靠推导的字段保持为空，不用 0 冒充。保存供应商返回的脱敏 usage JSON，便于未来重新解析。

## 4. 数据权威等级

每个 Token 与费用值记录来源：

- `PROVIDER_REPORTED`：上游响应或响应头明确给出。
- `GATEWAY_DERIVED`：由协议字段相加得到。
- `LOCAL_ESTIMATE`：本地 tokenizer 或套餐规则估算。
- `UNKNOWN`。

仪表盘和导出必须保留该字段。

## 5. 原始流水字段

CSV/JSONL 至少包含：

- `gateway_request_id`
- `upstream_request_id`
- `started_at`, `first_byte_at`, `completed_at`
- `duration_ms`, `time_to_first_byte_ms`
- 用户、团队、项目标识与名称快照
- Virtual Key ID、名称、前缀和末四位
- Subscription、真实凭证内部 ID 与安全指纹
- 供应商、产品、协议、模型
- HTTP 状态、业务状态、错误分类
- 是否流式、是否客户端取消、重试次数
- 各类 Token 与来源
- 官方按量成本、内部估算成本、币种
- 价格目录版本和价格快照
- Plan 窗口与额度归属标识
- usage 完整性标记

不导出任何完整密钥、请求头中的凭证或推理正文。

## 6. 按量 API 成本

使用事件发生时的价格快照计算：

```text
cost = input × input_price
     + output × output_price
     + cache_read × cache_read_price
     + cache_write × cache_write_price
```

**价格从哪来（#710 / F21-A 起）**：每个用量事件携带**它自己采用的单价**（`usage_event.price_*`，V64）与**当时算出的基础成本**（`base_cost_amount`，V66）。口径是「**行内冻结值；缺失则取该行 `occurred_at` 时刻的价目**」。

> 这一版之前，实现用的是**查询时刻的最新价目**——与本节的表述不符，后果是改一次价目，**历史报表金额跟着变**；而对"事件发生时还没有价格"的用量，等于**拿事后才发布的价格倒推**。本刀让代码对上了它自己的规格。

### 6.1 已知金额与未计价用量必须分开披露

**「有用量事实」不等于「有合法的价格事实」。** 二者在财务语义上必须分开，因此汇总同时给出三样东西：

| 字段 | 含义 |
|---|---|
| `cost.*` | **已知金额**：只包含价格依据支持的维度。**当 `pricingStatus != COMPLETE` 时它不是总额** |
| `pricingStatus` | `COMPLETE` / `PARTIAL` / `UNAVAILABLE` |
| `unpriced.*` | 未能计价的用量：四维 token 数 + 未计价事件数 |

数学定义（**这四条要照着实现，不要各自解释**）：

```text
COMPLETE    所有参与计算的维度都有价   → 已知金额 = 总额
PARTIAL     至少一维有价、至少一维无价 → 已知金额 = 已定价维度之和；总额 = 未知
UNAVAILABLE 没有任何维度可定价        → 已知金额 = 0；总额 = 未知
```

### 6.2 「0」与「NULL」是两种事实，不得互相冒充

- **合法的 0**：价格确实是 0 → `pricingStatus = COMPLETE`，`base_cost_amount = 0`
- **未知**：当时没有价格 → `price_status = UNAVAILABLE`，价格列与 `base_cost_amount` **均为 NULL**

把后者写成 0，会让"价格未知"在报表上读成"免费"。**判据已固化成测试**（`unavailableNeverMapsToZeroCost` / `completeWithZeroPriceRemainsLegitimateZeroCost`）——两者金额同为 0、含义相反。

某个维度**没有 token 就不参与计价**（没有 cache token 的事件，即使没有 cache 价目也是 COMPLETE）。

**一个组可以「已知金额 = 0」且 `pricingStatus = PARTIAL`，两者不矛盾**：组里可能既有"全维无价"的行（未知），也有"零 token、平凡可定价"的行（已知且为 0）。`UNAVAILABLE` 只在**该组每一行都无法定价**时才给出（判据是"不可定价的行数 ≥ 该组请求行数"），所以金额为 0 既可能是"全未知"，也可能是"可定价的那部分恰好是 0"——**看状态区分，别只看金额**。

### 6.3 其余口径

- **缓存节省（`savedByGatewayCache`）**：命中行没有对应的用量事件，因此按**该组命中中最晚一次的时刻**取价目。**这是近似**——一个 cache_key 的命中若横跨改价，整组会按较晚的价计价。**而且它可能是下界**：某维有 token 却在那一刻没有生效价目时，该次命中**无法计价**，节省额静默少算。`unpriced.unpricedHitEvents` 给出这类命中的次数——**> 0 即表示上面那个金额是下界**（#790）。
  这条**不影响 `pricingStatus`**：成本完不完整与节省完不完整是两个问题，把节省的缺口算进成本的状态会让一个从没被它碰过的数字显得不可信。
- **成本分摊（`cost_allocations`）仍在用"分配时刻的最新快照"**：改读冻结基座会牵动"同版本重跑覆盖历史"的语义，属独立决策，尚未切换
- **事后补价**：走**追加式**金额调整（#709 的 COST 维度），**不修改原始行、也不把 `UNAVAILABLE` 改成 `COMPLETE`**——"事件发生时没有价格"是一个不该被抹掉的事实

不同供应商缺少某类单价时不得擅自套用其他价格。官方账单仍是最终财务依据，Gateway 金额标记为“按官方价目估算”。

## 7. Plan 用量与成本

### 7.1 实际用量

无论供应商按请求、Token、积分还是 Prompt 系数扣减，系统都记录 Gateway 可观察到的请求和 Token。同时按签名目录规则计算 `plan_consumption_estimate`。

### 7.2 固定订阅成本

Plan 的供应商成本是周期固定费用，不能伪装成官方逐请求费用。

### 7.3 内部摊销

默认按项目在周期内的 Token 权重分摊：

```text
project_allocated_cost
  = plan_period_price
  × project_weighted_tokens
  ÷ all_projects_weighted_tokens
```

缓存、输入、输出 Token 是否使用不同权重由算法版本控制。首版使用总 Token 等权重，避免引入未经确认的复杂规则。结果标记 `INTERNAL_ALLOCATION`。

团队 Plan 可以同时生成：

- Subscription 总成本；
- 项目摊销；
- 成员/席位用量；
- 真实 Key 用量；
- 共享池与独占额度估算。

## 8. 余额与周期快照

后台任务定期抓取供应商状态。建议默认每 15 分钟一次，管理员打开详情页可以触发受频率保护的即时刷新。

快照展示：

- 数据来源；
- 最后成功时间；
- 周期起止与下次重置；
- 总量、已用、剩余与单位；
- 官方 API 是否可用；
- 本地估算与官方值的差异。

没有官方 API 时只根据经过 Gateway 的用量估算，明确提示无法观察绕过 Gateway 的使用。

## 9. 统计视图

管理员可按以下维度组合筛选：

- 时间；
- 用户、团队、项目；
- Virtual Key、真实凭证、Plan/Subscription；
- 供应商、产品、模型、协议；
- 状态和错误；
- Token 类型、费用类型。

普通用户只能查询自己的数据。

## 9.1 按请求排查与跨形态统计的权威口径（#719）

一次调用可能落在不同表里（转发 / 合并 / 缓存命中 / 网关拒绝 / MCP），**每张表的行粒度不同**——回答不同问题时必须用对应表并声明计数单位，混用是错账来源（评估与触发条件见 issue #719）：

- **问"这次请求发生了什么"（逐请求排查）**：`usage_event WHERE gateway_request_id = …`（转发与合并形态均逐请求落行），叠加 `request_usage_records` 的生命周期（IN_FLIGHT → 终态）；缓存命中形态查 `cache_hit_event`（该表**按 (租户, 缓存键, 层级, 秒) 唯一索引去重**：同一秒内 N 次相同命中折叠为 1 行，`gateway_request_id` 为该秒首条——不能据此还原"某一秒内的每一次命中"）。
- **问"总调用数 / 命中率"（聚合统计）**：先声明计数单位再取数——① 逐请求：`usage_event`（按幂等键）；② 秒级命中：`cache_hit_event`（**为秒级去重计数**，对同秒密集重复命中是低估口径——ROI 命中率现状即此口径）；③ 上游调用：`request_usage_records`（每次到达上游的调用恰好一行）。三者数不相等且各有含义，**不得相加或互换**。
- **网关拒绝（401/403/413/429/CONTEXT_* 等）当前不落行**（设计如此：拒绝不记账）；如需把拒绝计入统计，是独立的产品口径需求（涉及"拒绝要不要落行"），须先评审。
- **MCP 调用**以 `mcp_access_log` 为准（独立族、逐调用元数据），与模型面的口径分别成立、不合并比较。

上述"逐请求排查"的两条路径即当前平台的权威定义；`#705` 模型侧时间线按 gateway_request_id 回放采用同一口径。跨形态统一视图（只读 UNION）的启动条件记录于 #719。

## 10. 导出

- 管理员创建异步 ExportJob。
- 支持 CSV 与 JSONL，结果使用 gzip 压缩。
- 大范围导出按月度分区流式读取，禁止一次载入内存。
- 下载 URL 具有短期有效期并写入审计。
- 导出文件有 SHA-256 校验值。
- 临时导出文件可以自动清理，不影响数据库永久流水。

## 11. 与官方账单人工比对

优先匹配顺序：

1. 上游 request ID；
2. 真实凭证安全指纹 + 模型 + 精确时间；
3. Token 组合、状态和费用；
4. 聚合时间窗口。

供应商不返回 request ID 时，Gateway 无法保证逐请求一一对应；文档和导出必须标记可对账等级。（2026-09-10 兑现先行部分：导出的 `reconcileLevel` = PROVIDER_ID_BACKED/PARTIAL/LOCAL_ONLY 按 provider_request_id
覆盖度计算，文件 `local_caliber_note` 同步标注。）

**净额/含调整**（2026-09-18，#716）：这是**另一条轴**，与上面的可对账等级**互相独立**——"能不能按请求 ID 对上账单"与"数字里含不含修正"是两个问题，合进一个枚举就得为每种组合造一个值。导出任务另带 `adjustmentLevel` = `PRESENT`（至少一行被修正过，故 `net*` 列才是应对账的那一套）/ `NONE`（没有任何行被改过，`net*` 只是重复观察值），文件内同步 `;adjustments=present|none`。它**按任务声明**而非只在行上标注，因为消费者希望在读文件之前就知道 `net*` 列要不要看。

## 12. 永久保留与手动删除

UsageEvent 按月分区但不自动删除。管理员删除时：

1. 必须输入明确起止时间和过滤条件；
2. 先显示预计影响行数；
3. 二次确认；
4. 异步执行；
5. 写入不可删除的 AdminAuditEvent，包含操作者、条件、行数和校验摘要。

