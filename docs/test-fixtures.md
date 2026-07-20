# 协议测试夹具规范

Fixtures 用于证明 Gateway 不破坏协议，并让供应商变化能够被可重复地发现。所有内容必须是合成数据，不得提交真实凭证、客户提示词、代码或模型输出。

## 1. 目录结构

```text
test-support/src/main/resources/fixtures/
  anthropic-messages/
    basic-non-stream/
    streaming-with-usage/
    tool-use/
    prompt-cache/
  openai-responses/
    basic-non-stream/
    streaming/
    tool-call/
  openai-chat-completions/
    basic-non-stream/
    streaming/
  providers/<adapter-id>/<product-id>/<case-id>/
```

每个 case 可包含：

- `metadata.yaml`：fixture 版本、协议、能力、来源类别、预期结果。
- `request-headers.json`、`request-body.json` 或 `request-body.bin`。
- `response-headers.json`、`response-body.json` 或 `response.sse`。
- `expected-forward.json`：应保留/移除/注入的路径与 Header 规则，不保存 Secret。
- `expected-usage.json`：usage、request ID、cache token、解析来源。
- `expected-error.json`：仅用于本系统产生的错误。

## 2. metadata

```yaml
fixtureVersion: 1
id: anthropic-streaming-with-usage
protocol: ANTHROPIC_MESSAGES
streaming: true
upstreamStatus: 200
capabilities: [USAGE, REQUEST_ID, CACHE_TOKENS]
bodyClassification: SYNTHETIC
expectedByteTransparent: true
```

从官方公开文档手工构造的 fixture 标为 `DOCUMENTATION_DERIVED`；来自真实调用的响应必须先完全脱敏和人工审查，再标为 `SANITIZED_CAPTURE`。优先使用合成 fixture。

## 3. 必测用例

每个首版协议必须覆盖：

- 非流式成功和 SSE 流式成功。
- 工具调用、多字节 UTF-8、未知 JSON 字段、空 usage。
- cache read/write token 或等价字段。
- 400、401、403、404、429、500、502。
- DNS/连接失败、首字节超时、流中断、客户端取消。
- 超大但允许的 Header、分片跨 UTF-8/JSON token 边界。
- 入站多种鉴权 Header 被清除。
- `/v1/models` 按 Virtual Key 模型集合过滤。
- 供应商 request ID 与本地 request ID 同时记录。

## 4. Golden 规则

- 字节透明用例比较上游收到的 body SHA-256 与入站 body SHA-256。
- SSE 比较原始字节和事件顺序，不以反序列化后对象相等代替。
- Header 比较采用显式 allow/deny 断言；不把日期、连接等易变 Header 写死。
- Snapshot 更新必须由开发者审阅，不允许测试任务自动接受所有新 snapshot。
- 任何 Secret 模式扫描命中都使测试失败。

## 5. 真实供应商契约测试

真实测试按产品设置独立环境变量或挂载 Secret 文件，并由显式 profile 启用。测试必须：

- 使用最小成本模型和最小 token 输出。
- 给请求加可识别但不含个人信息的测试标签。
- 不打印请求/响应正文和凭证明文。
- 记录供应商 request ID、UTC 时间、产品目录版本和通过能力。
- 遇到余额不足或供应商故障标记 `INCONCLUSIVE`，不能伪装成通过。
- 完成后生成验证报告，但报告不得包含 Secret。

团队 Plan 的验证需要对应真实形态：共享池多 Key、席位 Key 或成员 Subscription Key。只验证单 Key 不足以把团队能力标为 `VERIFIED`。

## 6. Fixture 变更流程

1. 发现供应商行为变化，先新增失败 fixture。
2. 判断是兼容新增、破坏性变更还是文档误差。
3. 修复 Adapter，保留旧 fixture，除非官方明确停止支持。
4. 更新 provider catalog 的核验日期和状态。
5. 若改变公开行为，更新 Adapter 契约、API 契约或 ADR。

