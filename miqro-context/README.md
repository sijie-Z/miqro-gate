# miqro-context

MiQroKey **Context Attribution** 本地 Agent（CAA Spec v1.1 §3，Phase P1–P3）。

一把 Key、一个 Session、多项目穿插：Agent 在本机观察每个出站请求的**当前轮证据**
（工作目录 / 工具路径 / PR 链接 / Bash 路径），确定性地解析出"这一请求属于哪个项目"，
以**声明头**（claim）注入网关；网关仍以 `Key × Binding` 独立裁决归属。证据只在本机处理，
上行只有头部声明，绝不含正文。

```
Claude Code ──ANTHROPIC_BASE_URL──▶ miqro-context (127.0.0.1:8788) ──▶ MiQroKey Gateway ──▶ 上游
                                        │ 注入 X-Miqro-Project-Id / X-Miqro-Activity
                                        │ / X-Miqro-Claim-*（全部是"声明"，非权威）
                                        └ 剥离客户端伪造的 X-Miqro-* 头
```

## 快速开始

```bash
cd miqro-context && npm install && npm run build

# 1) 服务器侧（管理员，一次性）：把仓库登记到项目
#    POST /api/v1/admin/projects/{projectId}/repositories  { "repoKey": "github.com/acme/rocket" }

# 2) 写配置（或设置 MIQRO_CONTEXT_GATEWAY_URL）
mkdir -p ~/.miqro && cat > ~/.miqro/context.json <<'JSON'
{ "gatewayBaseUrl": "https://your-gateway.example", "listenPort": 8788 }
JSON

# 3) 启动 Agent 并接入 Claude Code
node dist/src/cli/index.js run        # 前台运行；或 miqro-context run（安装 bin 后）
node dist/src/cli/index.js doctor     # 自检：配置 / 网关 / Key / registry / git
node dist/src/cli/index.js status     # 最近的归属决策
```

Claude Code（settings.json 的 `env` 或 shell profile）：

```
ANTHROPIC_BASE_URL=http://127.0.0.1:8788
ANTHROPIC_AUTH_TOKEN=<你的 mqk_live_... 虚拟 Key>
```

`miqro-context install` 会打印这两步（**不会**改动你的任何配置文件）。
`miqro-context install --autostart` 额外注册**登录自启**（用户级、免管理员；
显式 opt-in）：Windows 写入 Start Menu 的启动文件夹、macOS 写 LaunchAgent、
Linux 写 systemd user unit——各平台激活提示由命令输出；
`miqro-context uninstall --autostart` 移除；`doctor` 会显示自启状态。
自启模式日志在 `~/.miqro/agent.log`。

## 归属是怎么判定的（Spec §5.3：作用域+分组+冲突，无打分）

1. **只有当前轮**新增消息参与证据（会话水位线差分；旧工具历史永不污染本轮 —— C15 回归）；
2. 证据来源与置信度：

   | 来源 | 提取 | 置信度 |
   |---|---|---|
   | prompt_url | 本轮用户消息中的 github PR/issue 链接 | HIGH |
   | tool_path | 本轮 tool_use 的 file_path/path → git remote | HIGH |
   | system_cwd | 请求 system 块的 `Working directory` → git remote | HIGH |
   | system_cwd | 非 git 目录（仅目录名，不能映射项目） | MEDIUM |
   | bash_cwd | 本轮 Bash 命令中的 cd/绝对路径 → git remote | MEDIUM |

3. 按"证据解析出的项目"分组：**≥2 个独立 HIGH 组 → AMBIGUOUS**（记冲突，不猜）；
   唯一 HIGH（或唯一 MEDIUM）组 → RESOLVED；本轮无信号 → 沿用会话当前上下文；
   全无 → UNATTRIBUTED；
4. **ActivitySegment**：仅当解析项目稳定变化才开新段——HIGH 立即切换，仅 MEDIUM 需连续两轮一致；
   同项目内文件/路径变化不动作（A→B→A 如实记三段）。

AMBIGUOUS / UNATTRIBUTED 时**不注入** `X-Miqro-Project-Id`——网关按失败关闭处理
（多绑定 Key → 400 CONTEXT_REQUIRED；单绑定 Key → SOLE_BINDING 正常执行）。

## 降级：Agent Failure / Direct Gateway Degraded（R8）

Agent 不是安全边界（网关才是）。Agent 不可用时，把 `ANTHROPIC_BASE_URL` 直接指回网关：
单绑定 Key 照常工作；多绑定 Key 无上下文时按设计失败关闭（400 CONTEXT_REQUIRED），
直到 Agent 恢复。`doctor` 会诊断当前处于哪种状态。

## 配置

`~/.miqro/context.json`（0600；可用 `MIQRO_CONTEXT_CONFIG` 换路径）：

```json
{
  "gatewayBaseUrl": "https://gateway.example.com",
  "listenHost": "127.0.0.1",
  "listenPort": 8788,
  "registryRefreshSeconds": 300,
  "virtualKey": "(可选) 需要开机即同步 registry 时填",
  "debug": false
}
```

环境变量：`MIQRO_CONTEXT_GATEWAY_URL`、`MIQRO_CONTEXT_PORT`、
`MIQRO_CONTEXT_REGISTRY_REFRESH_SECONDS`、`MIQRO_CONTEXT_VIRTUAL_KEY`、`MIQRO_CONTEXT_DEBUG=1`。

registry（repo → 项目映射）由 Agent 经 `GET /v1/context-registry`（虚拟 Key 认证，
只返回该 Key 绑定项目的条目）拉取；Key 未配置时，Agent 从第一条代管请求的
`Authorization` 中学习（仅存内存、绝不上行、绝不落盘）。

## 隐私

- 证据只在本机处理；上行仅 `X-Miqro-*` 声明头（网关侧只做审计，不构成授权）；
- 日志只记录归属决策元数据（状态/项目标签/来源/置信度/证据条数），**永不打印消息正文**；
- body 逐字节透传（方法/路径/查询/Authorization 均不改写）；SSE 逐块透传、中断双向传播。

## 开发

```bash
npm run typecheck   # tsc --noEmit
npm test            # tsc + node --test（35 用例：水位线/冲突模型/滞回/解析/头注入）
```

规格：`docs/context-attribution-implementation-spec.md` v1.1；设计稿：`docs/activity-context-design.md`。
