# 客户端与存量系统接入指南

> 来源：issue #742（PPT 差距清单「老系统改造接入—生态方向」+ 问答 Q13.5 的口径落地）。
> 目标：把「怎么把一个客户端（或存量系统）接进门」写成可照抄的步骤——三类姿势、每类给示例、并写明**不做**的边界。
> 相关：`proxy-and-cc-switch.md`（数据面协议语义）、`provider-catalog.md`（供应商侧）、`context-attribution-implementation-spec.md`（归属）。

## 1. 三类接入姿势（总览矩阵）

| 客户端形态 | 模型调用（同 `/v1` 三个协议族） | MCP / 工具生态 | 接入方式 |
|---|---|---|---|
| **开放客户端**（可自行配置 Base URL 与密钥） | ✅ 换地址 + 换钥 | ✅ | 最顺，零额外工具 |
| **可注入配置**（环境变量 / 配置文件 / 配置器） | ✅ | ✅ | 配置注入（CC Switch 类） |
| **完全封闭**（模型通道由平台封管下发） | ❌ 无服务端截流入口 | ✅ **MCP 层可接** | 只覆盖工具生态，见 §4 |

判断方法：该工具的「模型地址/密钥」是否可改——能改走姿势一/二；只有「MCP 服务器」可配的走姿势三。

## 2. 姿势一：开放客户端（模型调用）

1. **Base URL** 换成网关地址：`https://<网关域名或地址>`。网关数据面暴露三个协议族（`proxy-and-cc-switch.md` §4）：
   - Anthropic Messages：`POST /v1/messages`
   - OpenAI Responses：`POST /v1/responses`
   - OpenAI Chat Completions：`POST /v1/chat/completions`
2. **密钥**换成本人「我的密钥」页自助签发的虚拟密钥（`mqk_live_…`，仅创建时显示一次）。
3. 其余零改动：请求体、流式与工具调用原样透传。

示例（OpenAI 兼容客户端 / curl）：

```bash
curl https://<网关地址>/v1/chat/completions \
  -H "Authorization: Bearer mqk_live_…" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"你好"}]}'
```

> 归属（这笔记给哪个项目）：多数客户端一把 Key 对一个项目即可；跨项目场景见 §5（零配置的自动归属）。

## 3. 姿势二：可注入配置（环境变量 / 配置文件 / 配置器）

以 Claude Code 为例（Key 页「使用示例」一键复制的即以下形式），三平台等价：

```bash
# Windows cmd
set ANTHROPIC_BASE_URL=https://<网关地址>
set ANTHROPIC_AUTH_TOKEN=mqk_live_…
```

```powershell
# Windows PowerShell
$env:ANTHROPIC_BASE_URL="https://<网关地址>"
$env:ANTHROPIC_AUTH_TOKEN="mqk_live_…"
```

```bash
# macOS / Linux
export ANTHROPIC_BASE_URL="https://<网关地址>"
export ANTHROPIC_AUTH_TOKEN="mqk_live_…"
```

- **配置器形态（CC Switch）**：Key 页「接入 CC Switch」生成 `ccswitch://v1/import?…` 深链，在 CC Switch 中打开即完成 provider 导入——这是"配置注入器"的最小参考实现。
- 其它工具同理：把其等价配置（如 `OPENAI_BASE_URL`/`OPENAI_API_KEY`）指向网关与本钥。

## 4. 姿势三：完全封闭客户端 —— MCP 层接入

原理：这类工具的**模型通道**由平台封闭管理、配置平台下发，网关没有服务端截流的入口（模型层无解）；但多数工具**开放 MCP 服务器配置**——把 MCP 指向网关，它的工具调用就全部走网关、进审计与 MCP 调用日志。

步骤：

1. **管理端拿接入信息**：「MCP 服务」页 → 目标服务行「接入信息」→ 复制 `streamableHttpUrl` 或 `sseUrl`（网关地址 + `/mcpservers/{service}/…` 形态）。
2. **建消费者凭据**：「API 消费者」页新建消费者（API Key 仅创建时展示一次，或 JWT），能力作用域勾选 **`mcp:call`**（默认拒绝，须显式勾选）。
3. **在封闭工具里改 MCP 配置**：把 MCP 服务器地址换为第 1 步的地址、凭据换为第 2 步的消费者 Key。
4. **验证**：MCP 服务页「立即验证」确认连通；真实调用后在「MCP 访问日志」按服务/消费者查看调用记录（含工具名、TTFB、结论）。

> **实测样章**：真实封闭客户端（WorkBuddy）按本姿势的完整接入记录、证据与踩坑（配置路径/信任门/工具放行）见 [`workbuddy-mcp-onboarding-sample.md`](workbuddy-mcp-onboarding-sample.md)。

边界：本姿势**只覆盖该工具的工具生态**——它的模型对话本身仍走平台通道，不进网关账本。

## 5. 归属：接入后这笔账记给谁

- 一把 Key 对一个项目：天然自动（唯一绑定），无需任何配置；
- 一把 Key 多项目：客户端可带声明头（`X-Miqro-Project-Id`），或安装本机 `miqro-context` Agent 零动作自动归属（按工作目录/仓库映射自动判定，判定权在网关）；
- 无论哪种，判不出来时**拒绝或落未归属桶，绝不猜**（`context-attribution-implementation-spec.md`）。

## 6. 接入器（参考实现）

`scripts/onboarding/miqro-onboard.sh`：把上面各姿势的配置变成一条命令——**打印**可复制片段、
**写入**文件形式（幂等 + 时间戳备份 + 托管块）、**验证**凭据是否真被数据面接受。

```bash
S=scripts/onboarding/miqro-onboard.sh; GW=https://<网关地址>
sh $S print env --gateway $GW --key mqk_live_…                     # Claude Code（--shell posix|cmd|powershell）
sh $S apply claude-settings --gateway $GW --key mqk_live_… --file ~/.claude/settings.json
sh $S apply dotenv --gateway $GW --key mqk_live_… --flavor openai --file ./.env
sh $S print mcp --gateway $GW --key mqk_api_… --mcp-url <streamableHttpUrl>
sh $S verify --gateway $GW --key mqk_live_…                        # 200/404/401 按 §14.1 归因
```

工具在写盘前做与本文一致的**校验**：凭据平面不通用（用错一律 401）、虚拟密钥必须带 `.label`
后缀（裸钥统一 404 `virtual_key_invalid`）、网关地址须为 origin（尾随 `/v1` 带提示剥离）。
完整用法与设计边界（含为什么 `claude-settings` 依赖 jq、为什么 CC Switch 深链不在本工具内）
见 `scripts/onboarding/README.md`；输出形状以控制台「使用密钥」面板为准
（源头 `frontend/src/lib/ccswitch.ts`，改动需双侧同步）。

## 7. 明确不做的边界

- **网络层劫持**（hosts + 本地代理 + 自签证书）撬开封闭客户端：理论可行但成本高、有 TLS 与合规风险——**不做，也不建议**；
- **协议转换**：网关保持协议透明，转换归客户端侧（ADR-0002）；
- 需要"更顺的接入"时，做**接入器**（像 CC Switch 那样的配置注入器）比撬开网关爱更有意义。

## 8. 待补（#742 第③片）

- **封闭客户端实测**：选定一个真实封闭工具（如 WorkBuddy 类）完成 MCP 层接入端到端实测，并回填为本文样例。
