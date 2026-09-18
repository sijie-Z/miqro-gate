# 样章：WorkBuddy（封闭客户端）MCP 层接入实测（#742 第③片）

> 本样章是一次**真实封闭客户端**按 [client-onboarding §4](client-onboarding.md)（姿势三：完全封闭客户端——MCP 层接入）完成的端到端实测记录（2026-09-18）。所谓"封闭"= 其**模型通道由平台管理**（客户端自身的模型地址/密钥不可改），但它的**工具生态**可以接进来：把 MCP 指向网关，工具调用即全部走网关、进审计与 MCP 调用日志。本文每一步都有可复核证据；文中凭据一律为占位符。

## 1. 拓扑

```
WorkBuddy（封闭客户端）
   └─ MCP: streamable-http → https://<网关>/mcpservers/deepwiki/mcp
        └─ 网关 MCP 数据面（消费者凭据 mqk_api_…，作用域仅 mcp:call；工具 ACL 默认拒绝）
             └─ 上游：https://mcp.deepwiki.com/mcp（公开 DeepWiki MCP，只读文档查询）
```

- 上游选公开 DeepWiki MCP：只读、无需自带凭据，适合作为样章复现目标；
- 网关侧 MCP 服务 `deepwiki`：`transport=STREAMABLE_HTTP`，健康检查 `JSONRPC_INITIALIZE`（对真 MCP 上游比 HEALTH_PATH 有意义——见 §5 坑 ①）；
- 消费者 `workbuddy-e2e`：**能力作用域显式裁到 `mcp:call`**（runbook §3c 的最小权限纪律）。

## 2. 接入步骤（可照抄）

1. **管理端注册 MCP 服务**：endpoint 填上游 MCP 地址；健康检查切 `JSONRPC_INITIALIZE`；从服务行
   「接入信息」取 `streamableHttpUrl`（形如 `https://<网关>/mcpservers/<服务名>/mcp`）。
2. **建消费者凭据**：「API 消费者」页新建，`PATCH …/{id}/scope` 配 `["mcp:call"]`，
   得到 `mqk_api_…`（仅创建时展示一次；演示用 90 天到期）。
3. **客户端侧配置**：WorkBuddy 的用户级自定义 MCP 文件是 **`~/.workbuddy/mcp.json`（无点号）**：

   ```json
   {
     "mcpServers": {
       "miqrogate-deepwiki": {
         "type": "streamable-http",
         "url": "https://<网关>/mcpservers/deepwiki/mcp",
         "headers": { "Authorization": "Bearer mqk_api_…" },
         "timeout": 60000
       }
     }
   }
   ```

   > **坑 ②**：同目录下带点的 `~/.workbuddy/.mcp.json` 是**应用自身**的连接器代理配置
   > （应用日志里由它自己写入），往里写条目不生效；`connectors/<userId>/mcp.json` 亦不是
   > 运行时来源——该应用的连接器由云端（`copilot.tencent.com`）下发。
4. **通过信任门**：新条目的首次连接会被应用的安全策略跳过
   （日志 `[MCP Security] skipping untrusted server "…"`）。在应用界面确认信任即可
   （等价记录：`~/.workbuddy/mcp-approvals.json`，键 = `sha256(url 的 origin)::<serverName>`，
   值 = 授信时间戳）。**配置仅启动时加载**——改完重启应用。
5. **放行工具**：网关对工具调用**默认拒绝**（返回 `403 mcp_tool_unavailable` 并写审计，属安全
   默认值）。在「MCP 服务 → 工具」执行 **同步**（`POST …/tools/sync`）或手工登记，并置
   `ENABLED`。

## 3. 实测证据

| 环节 | 证据（2026-09-18，演示站） |
|---|---|
| 客户端连接与工具发现 | WorkBuddy 日志：`[MCP-Connect] ok configId=custom-mcp:miqrogate-deepwiki … tools=3`（`read_wiki_structure` / `read_wiki_contents` / `ask_question`） |
| 工具调用进网关审计（放行前） | `mcp_access_log`：6 行 `TOOL_UNAVAILABLE`，`toolName` 完整（三工具各 2 次）——**真实客户端发起的工具调用确实到达网关并被正确归因拒绝** |
| 放行后的成功转发 | `FORWARDED \| read_wiki_structure \| ttfb 617ms`，且返回体为真实 DeepWiki 目录内容（验证时以同一消费者凭据经网关调用，与客户端路径完全一致） |
| 归因链 | 全部行携带消费者 `workbuddy-e2e` 与服务 `deepwiki`，含 TTFB；`toolName` 精确到工具 |

## 4. 本次实测暴露并已修复的问题

1. **`tools/sync` 对严格 Streamable HTTP 上游 406**（issue #779 / PR #781）：同步客户端此前只发
   `Accept: application/json`，缺规范要求的 `text/event-stream` —— 对 DeepWiki 这类严格上游，
   "一键同步工具"不可用（错误面是 `502 TOOLS_SYNC_UPSTREAM_FAILED`，上游 406）。两步修复：Accept
   兼发双媒体类型（PR #781）+ SSE 响应帧解帧（PR #788）；修复前已用对照实验定位（同上游裸 `tools/list`：
   双媒体类型 200 / 单类型 406），修复后真机复现又暴露第二半（406 消失但上游改发 SSE 帧仍 502）。
   > 修复部署前的手工绕行：`POST …/tools`（MCP 原生工具用官方占位 `method=POST, path="/"`）+
   > `POST …/tools/{id}/status?status=ENABLED`。
2. **健康检查模式选择**：对真 MCP 上游用 `HEALTH_PATH` 会误判——若 checkPath 恰好落在会返回
   200 的 Web 端点上（本仓曾出现"指向门户 SPA 兜底页"的假健康），服务显示 HEALTHY 但协议不可用。
   选 `JSONRPC_INITIALIZE` 才是对协议层的真探测。

## 5. 边界与运维提示

- 本姿势只覆盖该工具的**工具生态**：它的模型对话仍走平台自有通道，不进网关账本；
- 接入完成后，工具调用的日常观测入口：**MCP 访问日志**（按服务/消费者筛，含 `toolName`/TTFB/结论）
  与审计链（含操作人）；
- 工具 ACL、消费者作用域、到期时间三者是独立的三道门（分别默认拒绝/全量/无期限需显式配）——
  接入清单建议固定为：**同步工具并放行 → 消费者裁 `mcp:call` → 设到期日**。
