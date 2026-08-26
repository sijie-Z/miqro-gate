# 供应商与 Plan 适配

> 调研基线：2026-07-17。供应商产品、模型、价格和额度规则变化较快，正式实现以签名目录与真实凭证契约测试为准。

## 1. 适配目标

管理员选择一个内置供应商产品，只录入该产品所需的真实 API Key。系统自动提供：

- 官方 Base URL 与鉴权方式；
- 支持的透明协议；
- 模型目录与能力标签；
- 按量价格或 Plan 规则；
- 模型列表查询策略；
- usage 解析器；
- 余额、周期剩余与团队额度查询策略；
- 凭证验证请求。

供应商产品必须区分按量 API、Coding Plan、Token Plan、团队 Plan 和企业 Plan，因为它们的 Key 与 Base URL 通常不能混用。

## 2. 能力状态

每个适配器分别记录：

- `DOCUMENTED`：官方资料确认设计。
- `IMPLEMENTED`：代码和 Mock 契约测试完成。
- `VERIFIED`：使用真实供应商 Key 完成契约测试。
- `DEGRADED`：部分能力只能本地估算或人工查看。

门户不能把 `IMPLEMENTED` 显示成 `VERIFIED`。

## 3. 首版 P0 候选

### 3.1 腾讯云 TokenHub

产品预设至少包括：

- Coding Plan；
- Token Plan 个人版；
- Token Plan 企业版专业套餐；
- Token Plan 企业版轻享套餐；
- 常规按量 API。

官方资料确认 Coding Plan 同时提供 OpenAI 与 Anthropic 兼容 Base URL；常规 TokenHub 提供 `/v1/models`。企业专业套餐采用积分池，多 Key 共享总额度，并允许为 Key 配置独占额度、总上限和模型；企业轻享套餐采用 Token 资源池和多 Key 配额。

团队建模：`MULTI_KEY_SHARED_POOL + DEDICATED_PLUS_SHARED/KEY_CAPPED`。

**实现状态：`IMPLEMENTED`（G3.2）** —— 适配器 `tencent-coding-plan`、`tencent-token-plan-personal`、`tencent-token-plan-enterprise-pro`、`tencent-token-plan-enterprise-lite`、`tencent-payg-api` 已落地。实际端点（管理员按地域/站点配置 Base URL）与路径归一化：

| 产品 | OpenAI Base URL | Anthropic Base URL | 模型列表路径 | `/v1` 前缀剥离 |
|---|---|---|---|---|
| Coding Plan | `https://api.lkeap.cloud.tencent.com/coding/v3` | `https://api.lkeap.cloud.tencent.com/coding/anthropic` | `/models` | 是 |
| Token Plan 个人版 | `https://api.lkeap.cloud.tencent.com/plan/v3` | `https://api.lkeap.cloud.tencent.com/plan/anthropic` | `/models` | 是 |
| 企业专业/轻享 | `https://tokenhub.tencentmaas.com/plan/v3` | `https://tokenhub.tencentmaas.com/plan/anthropic` | `/models` | 是 |
| TokenHub 按量 API | `https://tokenhub.tencentmaas.com` | `https://tokenhub.tencentmaas.com` | `/v1/models` | 否 |

鉴权：`Authorization: Bearer <api-key>`；入站凭证 Header 一律剥离。`fetchModels` 解析文档形状 `data[].id` + `name`（同时兼容 `display_name`）。`fetchPlanStatus` 按契约 §6 返回 `UNAVAILABLE`（2026-08-25 核验：所有 Tencent 产品均无公开余额/用量 API，仅控制台可见），绝不以本地估算冒充官方值。企业产品 `capabilities.teamPlan=true`、`PlanSnapshot.sharedPool=true`（多 Key 共享池建模）。`VERIFIED` 需要真实凭证契约测试 → `WAITING_FOR_CREDENTIAL`。

资料：

- [Coding Plan 概述](https://cloud.tencent.com/document/product/1823/130092)
- [TokenHub API 与模型列表](https://cloud.tencent.com/document/product/1823/130078)
- [Token Plan 企业版专业套餐](https://cloud.tencent.com/document/product/1823/130659)
- [企业版操作与多 Key 用量](https://cloud.tencent.com/document/product/1823/130661)

### 3.2 阿里云百炼 Model Studio

产品预设至少包括：

- Coding Plan；
- Token Plan 团队版；
- 百炼按量 API。

官方资料确认 Coding Plan 与 Token Plan 团队版分别使用专属 API Key 和 Base URL，并提供 OpenAI/Anthropic 兼容入口。团队版专属 Key 与普通按量 Key 不互通。

团队建模初始采用 `PER_MEMBER_SUBSCRIPTION_KEY`，但必须通过真实账号验证成员 Key、共享池和用量接口后才能标记 VERIFIED。

资料：

- [Coding Plan 概述](https://help.aliyun.com/zh/model-studio/coding-plan)
- [更多工具：Token Plan 团队版和 Coding Plan 端点](https://help.aliyun.com/zh/model-studio/more-tools)
- [API Key 与业务空间](https://help.aliyun.com/zh/model-studio/get-api-key)

### 3.3 智谱 GLM

产品预设至少包括：

- GLM Coding Plan 个人版；
- GLM Coding Plan 团队版；
- GLM 开放平台按量 API。

团队版使用席位，团队成员从“我的套餐”获取团队套餐专属 Key。系统需要保存 Seat 与成员 Key 的关联，而不是把团队额度简化为一个共享 Key。

团队建模：`PER_SEAT_KEY`。

**实现状态：`IMPLEMENTED`（G3.3）** —— 适配器 `zhipu-coding-plan-personal`、`zhipu-coding-plan-team`、`zhipu-payg-api` 已落地。官方端点（2026-08-26 核验 docs.bigmodel.cn）与路径归一化：

| 产品 | OpenAI Base URL | Anthropic Base URL | 模型列表路径 | `/v1` 前缀剥离 |
|---|---|---|---|---|
| Coding Plan 个人版/团队版 | `https://open.bigmodel.cn/api/coding/paas/v4` | `https://open.bigmodel.cn/api/anthropic` | `/models` | 是 |
| GLM 开放平台按量 API | `https://open.bigmodel.cn/api/paas/v4` | `https://open.bigmodel.cn/api/anthropic` | `/models` | 是 |

鉴权：`Authorization: Bearer <api-key>`；入站凭证 Header 一律剥离。usage 解析支持智谱文档形状 `prompt_tokens_details.cached_tokens`（缓存命中 → cacheRead，G3.3 加入共享 `TokenUsageParser`）。`fetchPlanStatus` 按契约 §6 返回 `UNAVAILABLE`（docs 索引无任何余额/用量查询 API，额度仅控制台可见）；团队版按席位独立限额（标准版 15k/5h、66k/周；高级版 35k/5h、155k/周），非共享池 → `PlanSnapshot.sharedPool=false`、`capabilities.teamPlan=true`。`VERIFIED` 需要真实凭证契约测试 → `WAITING_FOR_CREDENTIAL`。

资料：

- [Coding Plan 快速开始（Base URL 与 Key）](https://docs.bigmodel.cn/cn/coding-plan/quick-start)
- [Coding Plan 套餐概览](https://docs.bigmodel.cn/cn/coding-plan/overview)
- [团队版权益](https://docs.bigmodel.cn/cn/coding-plan/team)
- [对话补全 API（usage 形状）](https://docs.bigmodel.cn/api-reference/模型-api/对话补全)
- [Claude API 兼容（Anthropic 入口）](https://docs.bigmodel.cn/cn/guide/develop/claude/introduction)
- [团队成员获取专属 Key](https://docs.bigmodel.cn/cn/coding-plan/extension/coding-tool-helper)

**风险说明**：智谱官方文档未收录模型列表 API，`validateCredential`/`fetchModels` 的 `GET /models` 为 OpenAI 兼容惯例端点，真实凭证联调核验后若确认不存在需改用最小推理探针；Anthropic 兼容入口官方文档以 `x-api-key` 头为例（Anthropic SDK 默认），本适配器按平台惯例注入 `Authorization: Bearer`，兼容性待真实凭证核验。

### 3.4 MiniMax

产品预设至少包括：

- Token Plan 个人订阅；
- Token Plan for Teams；
- 按量 API。

官方资料说明，每个用户在其加入的每个 Team 中都有独立 Subscription Key；分配 Token Plan 席位或共享 Credits 后，该 Key 使用对应资源。Subscription Key 与按量 API Key 不互通。

团队建模：`PER_MEMBER_SUBSCRIPTION_KEY`，额度可以是席位订阅与共享 Credits 的混合模式。

**实现状态：`IMPLEMENTED`（G3.4）** —— 适配器 `minimax-token-plan-personal`、`minimax-token-plan-team`、`minimax-payg-api` 已落地。官方端点（2026-08-26 核验 platform.minimax.io）与路径归一化：

| 产品 | OpenAI Base URL | 模型列表路径 | `/v1` 前缀剥离 |
|---|---|---|---|
| 全部 3 个产品 | `https://api.minimax.io/v1` | `/models`（完整 `https://api.minimax.io/v1/models`） | 是 |

- 鉴权：`Authorization: Bearer <api-key>`；Token Plan 专属 Key 形如 `sk-cp-…`（与按量 Key 不互通）；入站凭证 Header 一律剥离。
- `fetchModels` 解析官方 list-models 形状 `data[].id/object/created/owned_by`（无 display name 字段，兼容 `name` 变体）。
- `fetchPlanStatus` 按契约 §6 返回 `UNAVAILABLE`（docs 索引无任何 Token Plan 余额/用量查询 API，额度与钱包仅控制台可见）。
- 团队版：席位 1:1 分配给成员（可转授、不重置用量），未分配席位的成员可经自己的 Subscription Key 消费共享 Credits 池 → `capabilities.teamPlan=true`、`PlanSnapshot.sharedPool=true`。
- Anthropic 兼容入口 `https://api.minimax.io/anthropic` 官方存在，但签名目录当前只声明 `OPENAI_COMPATIBLE`（JSON 不可改），待目录下一版签名补声明。

`VERIFIED` 需要真实凭证契约测试 → `WAITING_FOR_CREDENTIAL`。

资料：

- [Token Plan Overview](https://platform.minimax.io/docs/token-plan/intro)
- [MiniMax Pricing Overview](https://platform.minimax.io/docs/pricing/overview)
- [工具接入（Base URL 与 Key）](https://platform.minimax.io/docs/token-plan/other-tools)
- [OpenAI 兼容模型列表](https://platform.minimax.io/docs/api-reference/models/openai/list-models)
- [团队版定价（席位与共享 Credits）](https://platform.minimax.io/docs/guides/pricing-token-plan-team)

### 3.5 Kimi / Moonshot

产品预设至少包括：

- Kimi Code 会员 Key；
- Moonshot/Kimi 按量 API。

Kimi Code 同时提供 OpenAI 与 Anthropic 兼容入口；按量平台提供余额查询 API。公开资料暂未确认一个与智谱/MiniMax 类似的正式团队 Plan，因此首版不能把 Kimi 个人会员伪装为团队产品。企业协作先使用按量 API 账户能力，团队 Plan 等官方资料确认后再增加。

资料：

- [Kimi Code API 接入与 Base URL](https://www.kimi.com/code/docs/)
- [Kimi Code 权益与周期](https://www.kimi.com/zh-cn/help/kimi-code/benefits)
- [Kimi 按量 API 查询余额](https://platform.kimi.com/docs/api/balance)

### 3.6 百度千帆

产品预设至少包括：

- 千帆 Coding Plan；
- Token Plan 个人版；
- 千帆按量 API。

Coding Plan 和 Token Plan 个人版均有专属 Key 与 OpenAI/Anthropic 兼容 Base URL。公开资料中的团队形态需要进一步真实账号验证，首版先按个人 Plan 和企业按量产品实现。

资料：

- [千帆 Coding Plan](https://cloud.baidu.com/doc/qianfan/s/imlg0beiu)
- [Token Plan 个人版](https://cloud.baidu.com/doc/qianfan/s/Dmrabu8b6)

### 3.7 火山引擎方舟

产品预设至少包括：

- 方舟 Coding Plan；
- 方舟 Agent Plan；
- 方舟按量 API。

公开资料确认 Coding Plan 提供 Anthropic 与 OpenAI 兼容入口，Agent Plan 具有共享套餐额度和周期状态。余额/周期是否存在稳定官方 API、团队凭证拓扑如何表达，必须在实现前用真实账号确认；未确认前状态为 DOCUMENTED/DEGRADED。

资料：

- [方舟 Agent Plan](https://www.volcengine.com/activity/agentplan)
- [Coding Plan API 配置说明](https://www.volcengine.com/article/38138)

### 3.8 DeepSeek 官方 API

按量 API 预设，支持 OpenAI 与 Anthropic 入口、模型列表和官方余额查询。它不是 Plan，但属于客户常用的大陆官方来源，应作为基础适配器。

**实现状态：`IMPLEMENTED`（G3.1）** —— 适配器 `deepseek-payg-api` 已落地：透明转发（OpenAI 兼容 `/chat/completions` 与 Anthropic Messages `/v1/messages`）、`GET /models` 模型目录、`GET /user/balance` 余额（PAYG：余额即剩余可用额度，DeepSeek 不提供已用/周期，保持 null 不冒充 0）、usage 双形状解析（OpenAI 兼容 `prompt_cache_hit/miss_tokens` ↔ Anthropic `cache_read/creation_input_tokens`）。`VERIFIED` 需要真实凭证契约测试 → `WAITING_FOR_CREDENTIAL`。

资料：

- [DeepSeek 查询余额](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)
- [模型与价格、Base URL](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)
- [列出模型](https://api-docs.deepseek.com/zh-cn/api/list-models)

## 4. P1 候选

- 硅基流动 SiliconFlow 聚合 API；
- 阶跃星辰 StepFun；
- 小米 MiMo Token Plan；
- 其他 CC Switch 已支持且有稳定官方文档的大陆渠道；
- OpenRouter、Novita 等非大陆聚合平台。

进入 P1 不代表不支持透明转发；它表示尚未完成模型目录、usage、余额和真实凭证契约测试。

## 5. 团队 Plan 统一抽象

| 形态 | 示例 | 系统表达 |
|---|---|---|
| 多 Key 共享总池 | 腾讯企业 Token Plan | 一个 Subscription，多 Credential，共享 QuotaSnapshot |
| Key 有独占额度并可用共享池 | 腾讯企业专业套餐 | Credential quota + Subscription shared quota |
| 每席位独立 Key | 智谱团队版 | PlanSeat 一对一 Credential，可关联内部 User |
| 每成员 Subscription Key | MiniMax Team | Team + member Credential + seat/Credits resources |
| 仅个人会员 Key | Kimi Code 等 | PERSONAL，不伪装 TEAM |

管理员录入团队 Plan 时，可以逐个增加真实成员 Key；系统不要求供应商必须提供一个“团队主 Key”。

## 6. 余额和周期状态

余额能力分三级：

1. `OFFICIAL_API`：调用供应商公开且稳定的余额/额度接口。
2. `LOCAL_ESTIMATE`：用 Gateway 记录的 usage 和官方目录规则估算，只能覆盖经过 Gateway 的流量。
3. `UNAVAILABLE`：既无官方 API，也无法可靠估算，仅展示未知并告警管理员人工检查。

系统禁止通过模拟登录、保存控制台 Cookie 或网页抓取来获取余额。门户必须清楚标记数据来源和最后更新时间。

## 7. 模型与价格目录更新

- 每个应用版本内置一份可离线使用的目录。
- 模型、价格、周期规则等纯数据可以从带数字签名的目录热更新。
- 协议适配 Java 代码只能随正式版本升级，禁止下载并执行远程插件。
- 供应商实时 `/v1/models` 与内置目录合并，但不能自动授权新模型给项目。
- 每条用量事件保存价格目录版本和价格快照，防止历史成本随新价格变化。

### 7.1 目录签名与密钥管理（G2.1 实现）

内置目录位于 `backend/provider-adapters/src/main/resources/catalog/`：

| 文件 | 内容 |
|---|---|
| `provider-catalog.json` | 版本化产品清单（`version: 1` + `products[]`，当前 8 家 23 个产品，全部 `DOCUMENTED`） |
| `provider-catalog.sig` | Ed25519 签名，覆盖 `provider-catalog.json` 的精确字节（64 字节） |
| `keys/catalog-public.pem` | 校验公钥（SubjectPublicKeyInfo PEM） |

加载路径：`ProviderCatalog.loadBuiltIn()` → Ed25519 验签 → 严格 schema 校验（拒绝未知字段、非 https Base URL、未知枚举值、重复产品 id）→ 不可变定义列表。篡改目录、换钥签名或 schema 违规都会导致启动失败（`CatalogLoadException`），绝不静默降级。

**重签流程**（仅发布负责人执行）：

1. 修改 `provider-catalog.json` 后，用持有 Ed25519 私钥的发布环境重新签名：
   `openssl pkeyutl -sign -inkey <private-key.pem> -rawin -in provider-catalog.json -out provider-catalog.sig`
2. 验签确认：
   `openssl pkeyutl -verify -pubin -inkey keys/catalog-public.pem -rawin -in provider-catalog.json -sigfile provider-catalog.sig`
3. 私钥只存在于发布者安全环境，永不提交仓库、镜像或部署产物；公钥可随发布轮换（多版本目录预留 `CatalogKeyLoader` 的文件加载入口）。

目录是纯数据：schema 拒绝所有未知字段（含 `class`/`code` 等可执行字段），适配器解析只按 `adapterId` 查编译期注册表（`BuiltInAdapterRegistry`），被篡改或远程目录不可能加载代码。Base URL 为 `DOCUMENTED` 设计值，正式确认以 G3.x 真实凭证契约测试为准。

## 8. 供应商适配器验收模板

每个 ProviderProduct 必须完成：

- Key 验证成功、失败和过期场景；
- Base URL 与路径拼接；
- 普通与 SSE 流式请求；
- 模型授权与 `/v1/models`；
- 工具调用和推理字段透明性；
- 输入、输出、缓存 Token 解析；
- 上游 request ID 与错误码；
- 余额/周期状态及数据来源；
- 真实 Key 轮换；
- CC Switch 对应 Provider 的实际调用。
