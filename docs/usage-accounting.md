# 用量、成本与导出

## 1. 设计目标

- 每个请求都能追溯到用户、项目、Virtual Key、供应商产品、Plan 和真实凭证。
- 支持按量 API 与个人/团队/企业 Plan 的不同计费语义。
- 管理员可导出足够详细的原始记录，与供应商官方明细人工比对。
- 不保存提示词、代码、工具参数或模型回答正文。

## 2. 请求状态

建议状态：

- `SUCCEEDED`
- `UPSTREAM_REJECTED`
- `UPSTREAM_UNAVAILABLE`
- `CLIENT_CANCELLED`
- `TIMEOUT_BEFORE_FIRST_BYTE`
- `STREAM_INTERRUPTED`
- `AUTH_REJECTED`
- `MODEL_NOT_ALLOWED`
- `USAGE_PARSE_FAILED`

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

供应商不返回 request ID 时，Gateway 无法保证逐请求一一对应；文档和导出必须标记可对账等级。

## 12. 永久保留与手动删除

UsageEvent 按月分区但不自动删除。管理员删除时：

1. 必须输入明确起止时间和过滤条件；
2. 先显示预计影响行数；
3. 二次确认；
4. 异步执行；
5. 写入不可删除的 AdminAuditEvent，包含操作者、条件、行数和校验摘要。

