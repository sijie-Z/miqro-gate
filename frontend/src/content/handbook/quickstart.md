# 快速上手：10 分钟跑通第一条请求

> 假设你们已经部署好 MiQroGate（管理员已能登录）。本页分两条线：**管理员只做一次的准备**（5 分钟）+ **每位用户各自的接入**（5 分钟）。

## 0. 管理员准备（只在第一次部署后做一次）

1. **确认供应商产品**：左侧「模型管理 → 供应商」，找到你们的供应商（如 DeepSeek），点行内「接入文档」可查该产品的官方端点说明。
2. **录入真实 Key**：「模型管理 → 上游凭证 → 录入凭证」——选订阅/产品，把供应商官网申请的 API Key 粘进「API 密钥」字段（加密保存、不回显），保存后点「验证」确认供应商接受。
3. **建项目并拉人**：「访问与授权 → 项目 → 新建」（如 `core-ai`），在「成员」里把用户加进来；用户只有在项目里才能建 Key。
4. （可选）**录模型目录**：供应商行「模型目录」→「探测模型」一键从官方拉取；没有公开模型列表接口的产品可「手工补录」。

做完这 4 步，任何成员都能自助建 Key 了。详细说明见 [admin-guide.md](admin-guide.md)。

## 1. 登录

打开门户地址（管理员给你的 `https://<网关地址>`），用管理员发放的账号登录。

- 首次登录会要求**设置新密码**（一次性临时密码随即作废）；
- 空闲 30 分钟自动失效，会话最长 12 小时；被锁屏不等于退出（见 [faq.md](faq.md)）。

## 2. 创建你的虚拟密钥

左侧「总览 → 我的密钥」，右上「创建虚拟密钥」，填：

| 字段 | 填什么 | 例子 |
|---|---|---|
| 名称 | 给这把 Key 起个好认的名字 | `claude-code-笔记本` |
| 项目 | 你加入的项目 | `core-ai` |
| 供应商产品 / 授权 | 选管理员给你配的授权（决定可用产品） | DeepSeek 授权 |
| 用途 | **声明标签**（只用于展示/审计，不限制客户端） | Claude Code |
| 缓存策略 | 默认关闭；想省 token 可开（同请求秒回） | 关闭 |

下面会列出该授权允许勾选的模型（如 `deepseek-flash`），至少勾一个。

点「创建」后，**密钥只显示一次**（形如 `mqk_live_…`）。立刻复制保存。丢了只能轮换。

## 3. 接进你的工具（三选一）

**A. CC Switch 用户**：到「我的密钥」行内点「更多 ⌄ → 接入 CC Switch」，弹窗里点「复制深链」，在 CC Switch 里直接导入。

**B. Claude Code / Claude Desktop**（环境变量，三平台等价）：

```bash
# macOS / Linux
export ANTHROPIC_BASE_URL="https://<网关地址>"
export ANTHROPIC_AUTH_TOKEN="mqk_live_…"
```

```powershell
# Windows PowerShell
$env:ANTHROPIC_BASE_URL="https://<网关地址>"
$env:ANTHROPIC_AUTH_TOKEN="mqk_live_…"
```

**C. 任何 OpenAI 兼容客户端 / curl**：

```bash
curl https://<网关地址>/v1/chat/completions \
  -H "Authorization: Bearer mqk_live_…" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"你好"}]}'
```

Anthropic 协议客户端则把 Base URL 指向同一地址、路径用 `/v1/messages`——网关按协议原样透传，客户端与官方 SDK 完全兼容。更完整的接入矩阵（含完全封闭客户端走 MCP）见 [developer-guide.md](developer-guide.md)。

## 4. 验证与观察

- 第一条请求返回 200 即接入成功；
- 回门户「用量」页就能看到刚才那次调用（几分钟内入账，看「用量报表」的管理员视角同理）；
- 想把这次调用归到某个项目：单项目 Key 自动归属；跨项目客户端可带 `X-Miqro-Project-Id` 头（见 [developer-guide.md](developer-guide.md) §归属）。

## 卡住了？

| 现象 | 先查 |
|---|---|
| 401 Unauthorized | 密钥是否复制完整（`mqk_live_` 开头）、是否被停用/吊销（「我的密钥」状态列） |
| 404 且提示 virtual_key_invalid | 密钥无效或已轮换退役（网关对无效 Key 统一 404，防枚举）——到「我的密钥」用轮换后的新 Key |
| 403 / 模型不可用 | 该模型不在你的授权范围内——到「模型申请」提交申请，或请管理员加授权 |
| 一直转圈/超时 | 网关到供应商的连通或超时预算问题，把 requestId 发给管理员 |
| 其它 | [faq.md](faq.md) 按症状查 |
