# F19 供应商账单对账：契约先行稿（v0，待真实样本）

> 状态：CONTRACT-DRAFT（2026-09-09）。目的：把 feature-backlog F19（SCAFFOLD）中
> 「导入器契约可先行定义」落成可拍板的契约稿；**任何供应商解析器与真实匹配验收均
> WAITING_FOR_SAMPLE**（任一真实账单样本到位后按本稿实现并验证，不发明真实格式）。
> 关联：usage-accounting §11（四级匹配）、api-contract §6（异步任务形态）、
> F20（adjustment 追加机制，依赖本稿差异输出）、F23（可对账等级标记）。

## 目标与边界
- 目标：定义「官方账单 → 本地 usage_event」差异对账的**端到端契约**：上传格式（canonical）、
  匹配规则、报告输出；让 F20/F23 与 UI 可以并行准备。
- 边界：本稿不定义任何供应商私有格式的解析规则（等样本）；不写 usage_event；结果只读可审计；
  红线（不存正文等）全适用。

## Canonical 账单行（JSONL v0，逐行一条计费记录）
字段（全部 camelCase；金额以整数微单位或 decimal string + currency，不用浮点）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `provider_request_id` | string | 条件 | 上游请求 ID（chatcmpl-…/request_…）；无则走二级匹配 |
| `occurred_at` | string(UTC RFC3339) | 是 | 官方计费时间 |
| `model_id` | string | 条件 | 官方模型标识（二级匹配用） |
| `provider_product_code` | string | 条件 | 供应商产品（与本地 provider_products.product_code 对齐用） |
| `input_tokens` / `output_tokens` | int | 条件 | 三级匹配用 |
| `cache_read_tokens` | int | 否 | 缓存读 token（若账单单列） |
| `amount` | decimal string | 是 | 账单金额（原币种） |
| `currency` | string(ISO 4217) | 是 | 币种（默认 USD/CNY 由导入请求指定） |
| `status` | string | 否 | 官方状态（success/error/…），非 success 行只参与四态计数 |
| `provider_row_ref` | string | 否 | 账单原始行号/ID，报告回溯用 |

上传文件 = UTF-8 JSONL（逐行一个对象）；`.gz` 可选。首行不允许 BOM/元数据行（元数据放请求参数）。

## 匹配规则（usage-accounting §11，落实到引擎）
对每行账单记录，按优先级取本地 `usage_event`（同租户）：
1. **request ID 精确**：`provider_request_id` 等值（usage_event 有唯一 (tenant, provider_request_id)
   索引）。命中即 MATCHED（校验金额允许 ±0.5% 或 <0.01 币种差异 → 金额差并入报告，
   不改变匹配结论）。
2. **指纹 + 模型 + 时间**：真实凭证指纹（本地有）∩ model_id ∩ `occurred_at` 精确秒 ±60s，
   唯一候选即 MATCHED（低置信，报告中标记 `matchedBy: fingerprint+model+time`）。
3. **Token/状态/费用组合**：input/output（±cache）全等且唯一候选 → MATCHED（标记 level2）。
4. **聚合时间窗**：以上均不中的按 `(provider_product_code, occurred_at 的 5 分钟桶)` 聚合，
   与本地同桶汇总比较 → 桶级 PARTIAL（仅报告，不落逐行结论）。

结论四态：`MATCHED`（1–3 级，附 matchedBy）、`PARTIAL`（4 级桶级）、
`UNMATCHED_PROVIDER`（账单行无本地对应）、`UNMATCHED_LOCAL`（窗口内本地 usage_event 无
账单对应——由引擎从本地侧反查得出，需 provider_request_id 非空子集，避免"未对账行"把
无 ID 的本地行全部误报）。

## 端点契约（形态对齐 api-contract §6 异步任务）
- `POST /api/v1/admin/reconciliations`（SYSTEM_ADMIN，multipart 或 JSONL body）
  params：`provider_code`（目录内枚举）、`windowFrom/windowTo`（校验 ≤31 天且覆盖账单范围）、
  `currency`；→ 202 `{id, status: PENDING}`；异步执行：解析→匹配→报告落盘（不落 usage）。
- `GET /api/v1/admin/reconciliations/{id}` → 元数据 + 汇总
  `{total, matched, partial, unmatchedProvider, unmatchedLocal, amountDiffMicros}`。
- `GET /api/v1/admin/reconciliations/{id}/rows?state=&cursor=` → 明细页（四态过滤，按行）。
- 导出沿用导出任务基建（CSV，含 reconcile-level 列 → F23 一次打通）。
- 审计：`RECONCILIATION_CREATED/RUNNING/SUCCEEDED/FAILED`（含上传文件摘要，不存正文）。

## 拍板点
1. canonical v0 字段集是否够（尤其金额单位与 cache token 是否需要）；
2. 四级匹配中 2/3 级的"唯一候选"严格性（宁缺毋滥默认=仅唯一候选，重名不裁决）；
3. 报告是否要跨账单文件累积历史（默认否：每次导入=一次独立对账报告，可再次导入同一
   账单幂等覆盖该窗口报告，不写历史表；F20 才引入 adjustment 持久化）。

## 验收（样本到位后）
- 用任一真实供应商导出样本（CSV/JSONL 皆可）做适配器 → canonical；引擎四级匹配正确率
  人工抽检 100%；差异报告四态与金额差可看可导出；幂等重跑；审计完整。
- 无样本阶段：本稿 + canonical 校验器 + 纯合成 fixture 的引擎单测可先行（合成样本显式
  标注 `synthetic`，不冒充官方）。
