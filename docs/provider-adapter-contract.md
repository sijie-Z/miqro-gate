# Provider Adapter 契约

Provider Adapter 是编译期 Java SPI，用于描述“如何连接某个供应商产品”，不是跨协议转换层。每个 Virtual Key 只选择一个 Adapter、一个产品实例和一个凭证版本。

## 1. 边界

Adapter 可以：

- 解析产品允许的目标路径和上游 Base URL。
- 注入或替换上游鉴权 Header。
- 提供静态/官方模型目录并校验模型 ID。
- 从非流式响应或 SSE 事件中提取 usage、request ID、cache token 等元数据。
- 调用官方余额、配额、订阅状态接口。
- 校验凭证是否可用。

Adapter 不可以：

- 把 Anthropic 请求转换成 OpenAI 请求，或反向转换。
- 重新序列化正常业务请求/响应以“统一格式”。
- 修改 prompt、tool、thinking、stream 或缓存控制语义。
- 在多个供应商之间路由、重试或负载均衡。
- 抓取供应商控制台 HTML 获取 Plan 数据。
- 在日志、异常或指标标签中输出凭证明文和请求正文。

## 2. 核心类型

```java
public interface ProviderProductAdapter {
    String adapterId();
    Set<ProtocolFamily> protocols();
    TargetRequest resolve(RouteContext route, InboundRequest request);
    CredentialInjection credentialInjection(CredentialMaterial credential);
    Mono<CredentialCheck> validateCredential(ProviderClient client);
    Mono<ModelCatalogSnapshot> fetchModels(ProviderClient client);
    UsageObserver createUsageObserver(UsageContext context);
    Mono<PlanSnapshot> fetchPlanStatus(ProviderClient client, SubscriptionContext subscription);
    AdapterCapabilities capabilities();
}
```

建议值对象：

- `ProtocolFamily`：`ANTHROPIC_MESSAGES`、`OPENAI_RESPONSES`、`OPENAI_CHAT_COMPLETIONS`、`OPENAI_COMPATIBLE`、`VENDOR_NATIVE`。
- `CredentialInjection`：Header 名、前缀、需删除的入站鉴权 Header；敏感值使用可销毁类型，不进入 `toString()`。
- `TargetRequest`：已验证的上游 origin、path、query 和 Header 变换；禁止任意 URL。
- `UsageObservation`：输入/输出 token、cache read/write、供应商 request ID、finish/error、来源和解析置信度。
- `PlanSnapshot`：类型、总量、已用、剩余、周期、席位/成员、共享池、数据来源和获取时间。
- `AdapterCapabilities`：流式、模型发现、余额、Plan、团队 Plan、request ID、usage 位置。

接口的实际包名和小粒度可在 G0/G2 调整，但语义变化必须先更新本文或 ADR。

## 3. 产品、协议与凭证分离

一个供应商可能同时暴露多个产品或协议，例如 PAYG API、个人 Coding Plan、团队 Coding Plan。它们必须建模为不同 `ProviderProductDefinition`，不得仅靠一个 `vendor` 字符串分支。

产品定义至少包含：

```yaml
id: vendor-product-stable-id
vendor: vendor-id
displayName: Internal user-facing name
adapterId: vendor-adapter
protocols: [ANTHROPIC_MESSAGES]
baseUrlTemplate: https://api.example.invalid
credentialKind: API_KEY
subscriptionKinds: [PAYG, TEAM_PLAN]
modelCatalogMode: OFFICIAL_API
status: DOCUMENTED
```

Base URL 模板来自受签名目录或管理员选择，不能由普通用户控制。若允许管理员自定义兼容端点，必须显式标为 `CUSTOM_OPENAI_COMPATIBLE`，并通过 SSRF 校验。

## 4. 透明转发规则

- 路径只能匹配产品 allowlist；拒绝绝对 URL、userinfo、非预期 scheme、内网元数据地址和重定向逃逸。
- 删除所有入站供应商鉴权 Header，再注入当前凭证版本，防止 credential smuggling。
- 默认保留未知 Header；逐跳 Header 和本系统内部 Header 必须移除。
- 默认不跟随上游重定向。确需支持时，每一跳重新执行 origin allowlist。
- WebFlux 数据面使用有界内存和流式背压，不聚合完整 SSE 或大响应。
- usage 观察器必须旁路读取，不改变字节、事件边界和到达顺序。

## 5. 用量解析

每条记录标注 `usageSource`：

- `PROVIDER_RESPONSE`：供应商响应/SSE 明确给出。
- `PROVIDER_USAGE_API`：后续官方明细拉取。
- `LOCAL_ESTIMATE`：无法获得官方 usage 时的本地估算。
- `UNAVAILABLE`：不能可靠获得。

解析器必须容忍未知字段和新增事件。解析失败不得破坏用户请求；写入 `usage_parse_status=FAILED` 和脱敏诊断，以便后续补偿。禁止将估算冒充官方值。

## 6. Plan 与团队 Plan

`SubscriptionKind` 至少包括 `PAYG`、`INDIVIDUAL_PLAN`、`TEAM_PLAN`、`ENTERPRISE_PLAN`。团队 Plan 不能统一假设为一个共享 API Key：

- 共享配额、多 Key：subscription 维护共享池和 credential members。
- 按席位/成员 Key：subscription member 绑定用户/席位和独立 credential。
- 成员 Subscription Key + 共享 Credits：同时保存成员凭证和共享池余额。

`fetchPlanStatus` 的权威级别：

1. 官方 API 返回的余额/周期/额度。
2. 官方响应 Header 或官方账单明细推导。
3. 本地用量相对管理员记录的周期起点估算。
4. `UNAVAILABLE`。

管理员不手填“剩余量”作为事实。无法自动拉取时，页面明确显示数据不可用或本地估算及时间戳。

## 7. 生命周期与状态

Adapter/产品验证状态：

- `DOCUMENTED`：有官方文档证据，尚未实现。
- `IMPLEMENTED`：fixture 和 Mock 契约通过。
- `VERIFIED`：用真实凭证完成规定的最小请求、流式、usage/Plan 测试。
- `DEGRADED`：原已验证能力因供应商变化失败。
- `DISABLED`：管理员或目录发布禁用。

状态变化需要审计。生产默认目录只启用 `VERIFIED` 产品；管理员可以显式启用 `IMPLEMENTED`，页面必须持续警告。

## 8. 失败隔离

- 模型目录、余额和 Plan 拉取走 Control Plane 后台任务，不占用推理请求链路。
- Adapter 的后台失败只标记该能力陈旧，不自动吊销凭证。
- 上游业务错误原样返回，不跨凭证/产品自动重试。
- 网络连接建立前且请求体尚未发送时，可按统一策略进行一次安全重试；流式开始或非幂等请求发送后禁止重试。
- 供应商故障不自动切换；由管理员告警和通知，用户在 CC Switch 自主选择其他已配置项。

## 9. 包结构

```text
provider-spi/
provider-catalog/
providers/
  deepseek/
  tencent/
  zhipu/
  minimax/
  alibaba/
  moonshot/
  baidu/
  volcengine/
test-support/
```

核心 Gateway 只能依赖 `provider-spi`，不能出现 `if (vendor == ...)`。供应商模块通过 Spring 条件装配注册，重复 `adapterId` 启动失败。

## 10. Adapter 验收

每个产品至少提供：

- 产品 manifest 和官方文档证据链接、核验日期。
- 鉴权 Header fixture，断言入站 Key 被清除且真实 Key 不进快照。
- 普通、SSE、供应商 4xx、429、5xx、超时、取消和未知字段 fixtures。
- usage/cache token/request ID 提取测试。
- 模型目录和明确模型 ID 测试。
- 若声明 Plan 能力：个人及对应团队形态测试。
- WireMock/MockWebServer 端到端测试。
- 可选真实凭证测试，凭证只从环境 Secret 注入，默认不在公共 CI 执行。

