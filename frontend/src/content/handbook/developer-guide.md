# 开发接入指南

> 把应用或客户端接到 MiQroGate 数据面。先看 [quickstart.md](quickstart.md) 拿到「网关地址 + 虚拟密钥」，本页讲清协议、示例、错误语义与边界。

## 1. 端点矩阵（数据面）

同一个网关入口暴露三类协议族，**原样透传**（不改写请求体、不做跨协议转换）：

| 协议族 | 路径 | 典型客户端 |
|---|---|---|
| Anthropic Messages | `POST /v1/messages` | Claude Code / Claude Desktop / Anthropic SDK |
| OpenAI Chat Completions | `POST /v1/chat/completions` | 绝大多数 OpenAI 兼容客户端 |
| OpenAI Responses | `POST /v1/responses` | 新版 OpenAI SDK / Codex 系 |
| 模型清单 | `GET /v1/models` | **按虚拟密钥权限返回**（只列你可用的模型） |

鉴权统一 `Authorization: Bearer mqk_live_…`（虚拟密钥）。MCP 数据面见 §5。

## 2. 三个最小示例

**curl（OpenAI 兼容，含流式）**

```bash
curl https://<网关地址>/v1/chat/completions \
  -H "Authorization: Bearer mqk_live_…" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"你好"}],"stream":true}'
```

**curl（Anthropic Messages）**

```bash
curl https://<网关地址>/v1/messages \
  -H "Authorization: Bearer mqk_live_…" \
  -H "Content-Type: application/json" \
  -H "anthropic-version: 2023-06-01" \
  -d '{"model":"deepseek-flash","max_tokens":256,"messages":[{"role":"user","content":"你好"}]}'
```

**Node（OpenAI SDK）**

```ts
import OpenAI from 'openai';

const client = new OpenAI({
  baseURL: 'https://<网关地址>/v1',
  apiKey: process.env.MIQRO_KEY, // mqk_live_…
});
const r = await client.chat.completions.create({
  model: 'deepseek-flash',
  messages: [{ role: 'user', content: '你好' }],
});
```

**Python（Anthropic SDK）**

```python
import anthropic

client = anthropic.Anthropic(
    base_url="https://<网关地址>",
    api_key="mqk_live_…",
)
msg = client.messages.create(
    model="deepseek-flash", max_tokens=256,
    messages=[{"role": "user", "content": "你好"}],
)
```

> 更全的「三类客户端姿势」（开放客户端 / 环境变量注入 / 完全封闭走 MCP）与 CC Switch 深链，见 [../client-onboarding.md](../client-onboarding.md)。

## 3. 响应头与缓存

- 缓存命中时响应带 `X-MiqroKey-Cache: L1 | L2`（网关内部缓存）；缓存为 per-Key 显式开启，默认关闭；
- `GET /v1/models` 的返回即当前 Key 的可用模型白名单——**别猜模型名，先读它**。

## 4. 错误语义（排错必读）

错误响应为 RFC 9457 `application/problem+json`，含稳定 `code` 与 `requestId`：

| 状态 | code（示例） | 含义与处理 |
|---|---|---|
| 401 | `invalid_api_key`（MCP 面）/ 管理面会话失效 | 密钥缺失或不符；检查 `Bearer` 格式 |
| 404 | `virtual_key_invalid` | **`/v1` 面无效密钥统一 404**（防枚举）——被吊销/轮换退役的旧 Key 也走这里；换新 Key |
| 403 | `consumer_scope_denied` / 授权不足 | MCP 消费者缺 `mcp:call`；或模型不在授权范围（去「模型申请」） |
| 429 | `quota_exceeded` | 该作用域命中了「超限拒绝」配额规则；等重置或请管理员提额 |
| 502 | 上游错误（脱敏透传） | 供应商侧失败（如上游 400/500），`detail` 已含脱敏原因 |
| 504 | `mcp_upstream_timeout` | MCP 数据面上游预算耗尽（默认 60s，可调 1s–600s） |
| 413 | — | 请求体超限（反代 32MB 上限） |

排障拿不准时把 `requestId` 发给管理员，可在审计/日志侧定位同一请求。

## 5. MCP 接入（工具生态）

把 MCP 客户端指向网关（**Streamable HTTP**）：

```jsonc
// 例：MCP 客户端配置
{
  "mcpServers": {
    "erp": {
      "type": "streamable-http",
      "url": "https://<网关地址>/mcpservers/<服务名>/mcp",
      "headers": { "Authorization": "Bearer mk_consumer_…" }
    }
  }
}
```

- 凭据用**API 消费者**的 Key（不是虚拟密钥），能力作用域必须勾选 `mcp:call`（默认拒绝）；
- 旧式 SSE 客户端用 `GET /mcpservers/<服务名>/sse`（会话建立后按首帧 `endpoint` 事件回发 `POST …/message?sessionId=…`）；
- 服务名与可用工具由管理员在「集成管理 → MCP 服务」维护；连不上时让管理员看「MCP 访问日志」；
- 实测样章（封闭客户端 WorkBuddy 全流程 + 踩坑）：[../workbuddy-mcp-onboarding-sample.md](../workbuddy-mcp-onboarding-sample.md)。

## 6. 归属（这笔账记给谁）

- **单项目 Key**：自动归属，无需配置；
- **跨项目**：a) 客户端带 `X-Miqro-Project-Id: <项目 ID>` 声明；b) 安装本机 `miqro-context` Agent——按工作目录/仓库自动判定，零客户端改动；
- 判定优先级与冲突处理见 [../activity-context-design.md](../activity-context-design.md)。

## 7. 兼容性与边界（提前避坑）

- **不做跨协议转换**：Anthropic 客户端配 Anthropic 路径、OpenAI 客户端配 OpenAI 路径（CC Switch 用户由 CC Switch 负责转换，与本网关无关）；
- **流式/工具调用逐字节透传**：SSE 事件、tool calls、推理字段不被改写；断连会计为 `CLIENT_CANCELLED` 并如实记账；
- **`max_tokens` 坑（实测）**：上游 V4.1 系思考预算与 max_tokens 同池——预算小的请求可能出现「200 + 空内容 + finish=length」；读模型文档设足 `max_tokens`（例：≥1024）；
- **不自动跨供应商故障切换**：失败原样透传；首字节前的安全重试至多一次；
- **正文不留存**：默认不保存提示词与回答（审计只记元数据）；「内容留痕」为管理员显式开启的合规能力。

## 8. 更多

- 管理 API（自动化运维）：[../api-contract.md](../api-contract.md) 与 `docs/openapi/`；
- 本地/多环境联调：[../live-integration-guide.md](../live-integration-guide.md)；
- 问题速查：[faq.md](faq.md)。
