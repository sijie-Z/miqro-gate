# MiQroKey Context Attribution Architecture —— 实现级 Spec v1（AI 可直接开工）

- 日期：2026-09-16
- 状态：**v1 实现规格（待确认 4 个开放项后即可开工，见 §10）**
- 前置文档：`docs/activity-context-design.md`（设计稿，issue #629）；本文是其**实现级展开**，并纳入对架构提案的**实证修正**（§0.2）。
- 命名：**MiQroKey Context Attribution Architecture（CAA）**

---

## 0. 总纲

### 0.1 四句原则（不可违背）

1. **One Key, One Identity** —— CC Switch 永远只有一条配置、一把 Key；Key 只回答"我是谁"。
2. **One Session, Many Activities** —— 一个 session 里项目随意穿插；归属是**请求级**，不是 session 级。
3. **One Request, One Deterministic Attribution** —— 每个请求在进入 Gateway 时即产生确定性的归属四元组（project/activity/source/confidence）。
4. **When Evidence Is Insufficient, Do Not Guess** —— 证据不足 → `UNATTRIBUTED`；证据冲突 → `AMBIGUOUS`；都进"未归属桶"，**绝不猜、绝不静默错账**。

分层：`Identity（Virtual Key）→ Activity Context（每请求）→ Authorization（Binding/Grant）→ Usage（Billing）`。

大厂对照：AWS Bedrock 把 per-user/per-workload 归因与 per-request attribution 分层，并建议由共享客户端或网关在每次调用统一附加 request metadata；session 级标签不能替代请求级标签。本方案与其同构。

### 0.2 对提案的实证修正（2026-09-16 真机实验，证据可复现）

| # | 提案原设想 | 实证结论 | 修正 |
|---|---|---|---|
| M1 | Local Proxy（隐含 MITM 或改造） | Claude Code 直接接受 `ANTHROPIC_BASE_URL=http://127.0.0.1:PORT` 明文本地端点并正常发送每个请求（实验 1/4） | **Local Agent 不是拦截代理，而是被配置为端点本身**：客户端→Agent 走 localhost 明文，Agent→Gateway 走 HTTPS。零证书、零 MITM |
| M2 | 依赖 hooks 收集 cwd/工具证据 | 本机（桌面 3p 环境）settings 提供的 hooks 被静默弃（实验 3）；且 hooks 属于"敏感设置"受审批门控 | **v1 从请求体提取证据**：Claude Code 每个请求的 `system` 块天然含 `Working directory`/workspace（实验 6），工具调用（Read/Edit/Bash 输入）与消息内 URL 均在 body 中。**hooks 只作可选增强**（标准 CLI 环境可用时） |
| M3 | 动态上下文（session 内切换） | 头部经 env/settings 注入时**在会话启动时解析一次**，session 内不可变（二进制核实）；无 per-turn hook（官方 open issue #21531） | 动态切换**必须**经由 Local Agent（每请求读取当前 Context 状态）；"静态头"是退化模式（每 session 一个项目） |
| M4 | apiKeyHelper 动态头 | 官方唯一动态通道，但其头部输出在本机环境未生效（实验 3），待干净环境复核 | v1 **不依赖** apiKeyHelper；列为 v2 的"无代理轻量模式"候选 |

其余部分（Identity/Context/Authorization/Usage 分层、确定性 Resolver、Evidence 表、Activity 维度、高基数分层、后缀降级为 Compatibility）**全部采纳**。

### 0.3 与既有 PR 的关系（重要和解）

本架构的授权链（§6）明确包含 **Binding Lookup：`virtual_key + project_id → binding → grant`**，验收 Case 9/10 也要求"一个 Key 对多个项目授权 / 撤销某个 project binding 后其余项目不受影响"——**这正是 PR #615 已实现的引擎**（表 `key_project_binding` 每行自带 `grant_id`、快照按 `(keyId, tag)` 装载、门控按绑定 grant、轮换复制、成员移除按绑定处理）。提案 §20 同样保留后缀为 Compatibility/Explicit Context。

**处置建议**：解冻 #615，作为 §6 授权层（Phase 4）的实现起点；选择器改为"上下文头优先、后缀兜底"（一行解析顺序差异）。若 leader 决定完全不保留 binding 概念（改为纯用户×项目授权），则 §6 与迁移按 `docs/activity-context-design.md` §6-B 线替换——**但本 Spec 默认按提案的 Binding 线编写**。

---

## 1. 最终用户体验（验收叙事）

CC Switch（唯一一条配置）：

```
供应商：MiQroKey
API Key：mqk_live_xxxxxxxx…
API 请求地址：http://127.0.0.1:8788      ← 指向本机 MiQro Context Agent
```

Session #123 内：

```
① 帮我修 MiQi #104            → MiQi        180k
② 审一下 MiqroForge PR #1083  → MiqroForge   90k
③ 给 quant 写一份文档          → quant        70k
④ 回到 MiQi 把测试补上         → MiQi        +40k
⑤ 帮我想个新支付方案           → UNATTRIBUTED
```

全程：Key 不变、CC Switch 不动、Session 不重开。

---

## 2. 组件与数据流

```
┌──────────────┐   one key    ┌─────────────────────────────┐
│  CC Switch   │─────────────▶│        Claude Code          │
└──────────────┘              │  (ANTHROPIC_BASE_URL=127.0.0.1:8788) │
                              └──────────────┬──────────────┘
                                             │ HTTP (localhost, 明文)
                                             ▼
                              ┌─────────────────────────────┐
                              │   MiQro Context Agent       │
                              │  collector → resolver →     │
                              │  session/activity state     │
                              │  → header injector          │
                              └──────────────┬──────────────┘
                                             │ HTTPS
                                             ▼
                              ┌─────────────────────────────┐
                              │        MiQro Gateway        │
                              │ Identity → Context 提取/校验 │
                              │ → Binding → Grant → 模型/凭证│
                              │ → Usage Event（含证据引用）  │
                              └──────────────┬──────────────┘
                                             ▼
                                   Provider / Billing
```

Context Agent 每收到一个客户端请求：
1. **Evidence 采集**：解析 body（system 块 env、tool_use 输入、消息内 URL）+ 本地状态（上次解析结果、session 内活动段）；
2. **Resolver**：确定性规则（§5）→ `RESOLVED | AMBIGUOUS | UNATTRIBUTED`；
3. **注入头部** → 转发 Gateway（流式透传，见 §3.4）。

---

## 3. 客户端：miqro-context（TypeScript / Node ≥ 20，单可执行分发）

### 3.1 目录结构

```
miqro-context/
├── src/
│   ├── collector/
│   │   ├── body.ts          # Anthropic Messages body 解析（system env / tool_use / URLs）
│   │   ├── git.ts           # cwd → git root/remote（本地只读）
│   │   └── url.ts           # 正则: github.com/{owner}/{repo}/(pull|issues)/{n} → repo
│   ├── resolver/
│   │   ├── evidence.ts      # Evidence 模型 + 置信度
│   │   ├── resolver.ts      # 确定性阶梯（§5）
│   │   └── registry.ts      # projectRegistry 本地缓存（repo→project 映射，服务端同步）
│   ├── state/
│   │   ├── session.ts       # sessionId（X-Claude-Code-Session-Id）→ 活动段
│   │   └── activity.ts      # ActivitySegments（证据变化即开新段；activityId 本地生成 uuid）
│   ├── proxy/
│   │   ├── server.ts        # http.createServer，仅监听 127.0.0.1
│   │   ├── forward.ts       # 向 Gateway 转发（undici，SSE 逐块透传、透传 abort、不改 body）
│   │   └── inject.ts        # 头部注入 + 剥离客户端伪造的同名头
│   └── cli/
│       ├── install.ts       # 写入/校验 CC Switch 或 Claude Code 指向本代理；自启注册
│       ├── status.ts        # 显示当前解析上下文、最近请求归属
│       └── doctor.ts        # 连通性/证书/端口/回退诊断
├── package.json
└── README.md
```

### 3.2 状态机（每个 session 一份）

```
SessionStart(首个请求到达) ──▶ current=resolve(body) ──▶ 归属请求
        │                              │
        │  证据变化(URL/文件路径/工具目标) │  证据不足
        ▼                              ▼
  ActivitySegment 切换          UNATTRIBUTED(挂未归属桶)
        │  冲突证据                              │ 出现新证据
        ▼                                       ▼
  AMBIGUOUS(挂未归属桶, 记录冲突) ──────────▶ 重新解析
```

规则：**同一 ActivitySegment 内证据集合只增不减**；段边界 = Resolver 产出发生变化时（新 URL / 新 repo 路径）；`activityId` 段内稳定。

### 3.3 转发契约（Agent → Gateway）

- 透传方法/路径/查询串/请求体/`Authorization`（**逐字节**，不分帧、不重排 —— 不得违反"透明代理不重排请求 JSON"红线）；
- 剥离客户端带来的 `X-Miqro-*` 头（防伪造），注入：
  ```
  X-Miqro-Context: <project_tag>          # RESOLVED 时
  X-Miqro-Activity: <activity_id>          # 段内 uuid
  X-Miqro-Context-Source: github_pr|cwd_git|tool_path|session_cwd|none
  X-Miqro-Context-Confidence: HIGH|MEDIUM|LOW|NONE
  X-Miqro-Context-Status: RESOLVED|AMBIGUOUS|UNATTRIBUTED
  ```
  **头名按 Claude Code 敏感词门控核过（§0.2 M2/M4 同族约束，防未来换渠道时被拦）**；AMBIGUOUS/UNATTRIBUTED 时**不注入** `X-Miqro-Context`（只注入 status/source），由 Gateway 落未归属桶。
- 响应（含 SSE）**逐块透传**；上游 4xx/5xx 原样返回；连接中断双向传播（客户端 abort → 转发 abort）。
- Agent 不做任何业务判断（不鉴权、不改 body、不重试）。

### 3.4 运行与安装

- 监听 `127.0.0.1:8788`（可配；`MIQRO_AGENT_PORT`）；
- 自启：Windows（登录计划任务 / 启动目录）、macOS（launchd user agent）、Linux（systemd user unit）；`miqro-context install` 一键注册并写配置；
- 配置：`~/.miqro-context/config.json`（gatewayBaseUrl、port、registrySyncInterval、logLevel）；
- **Fail-open 策略**：Agent 不可用时 Claude Code 直接不可达（端点即 Agent）→ 提供 `doctor` 诊断 + 文档化的应急回退：把 base_url 指回 Gateway 直连（此后无上下文 → 请求落 `UNATTRIBUTED` 或后缀兜底，仍然可用）。
- 隐私：Evidence 只在本机处理；上行只有 §3.3 的头部与（可选）证据摘要；`logLevel=debug` 也**禁止打印消息正文**。

---

## 4. 传输契约与 Gateway 校验（权威在服务端）

### 4.1 优先级（确定性，无猜测）

```
X-Miqro-Context 头（Agent 注入）
      │ 无
      ▼
密钥后缀（Compatibility/Explicit，解析规则同 #615）
      │ 无
      ▼
Key 的 primary binding（单绑定 Key 的兜底）
      │ 存在多个绑定
      ▼
400 CONTEXT_REQUIRED（fail-closed，不静默归账）
```

### 4.2 失败语义

| 情形 | 响应 |
|---|---|
| 头声称的 project 无该 Key 的 ACTIVE binding | **403 `CONTEXT_NOT_ALLOWED`**（可调试；防枚举的 404 只留给"密钥本身无效"） |
| 头非法（tag 格式/长度/未知图形字符） | 400 `CONTEXT_INVALID` |
| 多绑定且无任何上下文 | 400 `CONTEXT_REQUIRED` |
| `X-Miqro-Context-Status=UNATTRIBUTED/AMBIGUOUS` | 不拒绝；正常鉴权后落**未归属桶**（§7.3），usage 记 source/status |

### 4.3 防伪

头部**不参与 HMAC**、不承载权限；服务端始终以 `(keyId, context-tag)` 查快照绑定为准；`X-Miqro-*` 头**剥离后不转发上游**。

---

## 5. Context Resolver（本地，确定性规则，禁止 LLM 猜测）

### 5.1 Evidence 模型

```ts
type Evidence = {
  source: 'prompt_url' | 'tool_path' | 'bash_cwd' | 'system_cwd' | 'git_remote';
  value: string;            // 规范化值（repo key / 绝对路径）
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  observedAt: number;
  requestId: string;
};
```

### 5.2 采集规则（v1 从请求体 + 本地只读）

| 来源 | 提取 | 置信度 |
|---|---|---|
| **prompt_url** | 最新 user 消息中 `github.com/{owner}/{repo}/(pull|issues)/\d+` → `owner/repo` | HIGH |
| **tool_path** | body 中最近 K=8 个 tool_use 的 `file_path/notebook_path/path` → 向上找 git root → remote | HIGH |
| **bash_cwd** | 最近 Bash `command` 中 `cd <path>` / 命令内绝对路径 → git root → remote | MEDIUM |
| **system_cwd** | system 块 `Working directory:` → git root/remote；非 git 仓库则按目录名 | MEDIUM |
| **git_remote** | 上述任一 cwd 成功解析出的 remote |（随来源） |

（hooks 增强（标准 CLI）：`CwdChanged`/`PostToolUse`/`UserPromptSubmit` 作为额外 Evidence 来源，置信度同表。）

### 5.3 解析阶梯（确定性）

1. 取**当前 ActivitySegment** 内全部 Evidence；
2. 若存在两组**同级 HIGH** Evidence 指向不同 project → `AMBIGUOUS`（写冲突明细）**不再降级取次级证据**；
3. 否则按置信度取最高级；同项目证据合并；
4. 由此得到 repo key → 查 **Project Registry**（repo→project_tag 映射）→ `RESOLVED`；
5. Registry 未命中该 repo → `UNATTRIBUTED`（**不得**用目录名硬猜项目）。

**开放确认点（§10-①）**：提案案例 5/6 的字面存在张力（case5: cwd 中性 + PR URL → 按 PR；case6: cwd 指 A + PR 指 B → AMBIGUOUS）。本 Spec 默认：**同级冲突 → AMBIGUOUS**；并建议阶梯将"**最新一轮用户消息里的 URL**"视为高于持久 cwd 的**意图信号**——需 leader/ChatGPT 确认后固化进规则表。

---

## 6. Gateway 改造（Java / control-plane 无关；全部在 gateway-app + route-snapshot + domain）

### 6.1 类清单

| 类 | 动作 |
|---|---|
| `VirtualKeyResolver` | **保留**（Identity 层不动） |
| **`RequestContextResolver`（新增）** | 从请求头提取 `ContextClaim{tag, activityId, source, confidence, status}`；剥离 `X-Miqro-*`；非法/缺失 → 按 §4.1 阶梯产出 `ResolvedContext` |
| `RouteSnapshot.binding(keyId, tag)` | 已有（#615）；新增 `bindingById(keyId, projectId)` 备用 |
| `AuthContext` | 增加 `projectContext`、`activityId`、`contextSource`、`contextConfidence`、`contextStatus` |
| `ModelsController` / `ProxyController` 门控 | 使用解析出的 binding.grant（#615 已改） |
| `ProxyController` 管线 | 新增第 2.5 步 Context 提取/校验（§16 管线），usage 事件携带上下文四元组 |
| `UsageEvent`（domain） | 增 `sessionId / activityId / contextSource / contextConfidence / contextStatus` 字段 |

### 6.2 请求管线（最终态）

```
HTTP → Credential Authentication → Virtual Key Identity
     → Context Extraction（头/后缀/兜底）→ Context Validation（binding 存在性）
     → Binding Lookup → Grant/Model Authorization → Provider Routing → Usage Event
```

### 6.3 未归属桶的落账

`UNATTRIBUTED/AMBIGUOUS`：鉴权与路由正常执行（可用 primary binding 的 project **仅用于取 grant/凭证**），但 **usage 的 project_id 落"未归属桶"**（§7.3），并记录 source/status/evidence 引用——**账单可解释、可事后重分类**。

---

## 7. 数据库（追加迁移，不动既有）

### 7.1 V54 `usage_event` 增列（全部可空，默认 NULL——纯增量）

```
session_id            varchar(64)     -- X-Claude-Code-Session-Id
activity_id           uuid
context_source        varchar(32)     -- HEADER_TAG | SUFFIX | PRIMARY | UNATTRIBUTED | AMBIGUOUS
context_confidence    varchar(16)     -- HIGH | MEDIUM | LOW | NONE
context_status        varchar(16)     -- RESOLVED | AMBIGUOUS | UNATTRIBUTED
```

### 7.2 V55 `request_context_evidence`（审计表，仅元数据，不含正文）

```
id            uuid PK
tenant_id     uuid NOT NULL
request_id    varchar(64) NOT NULL   -- 网关 request id
source        varchar(32) NOT NULL   -- prompt_url|tool_path|bash_cwd|system_cwd|git_remote|header
value         varchar(512) NOT NULL  -- 规范化证据值（repo key / 路径 / tag）
confidence    varchar(16) NOT NULL
observed_at   timestamptz NOT NULL
索引：(tenant_id, request_id)
```

### 7.3 未归属桶 = 租户级系统项目

- 每租户自动确保存在系统项目 `UNATTRIBUTED`（code=`UNATTRIBUTED`，不可删除、不可被正常建 Key 选择；member/授权按需后台配置）；
- usage 的 `project_id` 直接指向它——FK 干净、报表天然可见、支持"事后重分类"（admin 把某批请求改归真实项目，写审计）。

### 7.4 Project Registry（repo ↔ 项目）

```
project_repositories
  id uuid PK, tenant_id uuid, project_id uuid FK,
  repo_key varchar(200)   -- 规范化 github.com/{owner}/{repo}（小写、去协议/尾斜杠/.git）
  created_at/updated_at
  唯一：(tenant_id, repo_key)
```

管理端：项目详情增加"关联仓库"维护（前端表单 + `POST/DELETE /api/v1/admin/projects/{id}/repositories`）。

---

## 8. API 增量

| 端点 | 用途 |
|---|---|
| `GET /api/v1/me/context-registry` | Agent 同步 repo→project_tag 映射（仅返回该用户有权限的项目；ETag/增量） |
| `POST /api/v1/admin/projects/{id}/repositories` / `DELETE …/{repoKey}` | 维护仓库映射 |
| `GET /api/v1/admin/usage/attribution?status=UNATTRIBUTED` | 查未归属/歧义请求（含证据明细 join） |
| `POST /api/v1/admin/usage/attribution/reassign` | 事后重分类（写审计，幂等） |

（OpenAPI 基线随之导出更新；契约文档同步 api-contract。）

---

## 9. 测试矩阵（AI 实现时必须全部自动化）

| # | 场景 | 期望 |
|---|---|---|
| C1 | 一 Key + 一项目 session | 全部落该项目（source=HEADER_TAG/HIGH） |
| C2 | 同 session A→B→A | usage 按序 A/B/A，binding 各自生效 |
| C3 | A 项目代码 → B 项目 PR URL | 段切换 A→B，逐请求归属 |
| C4 | A 代码 → B 文档 → C 流程图 | A/B/C 三段 |
| C5 | cwd 中性 + prompt 明确 PR URL | 按 PR repo 归属 |
| C6 | cwd 指 A + prompt PR URL 指 B | **AMBIGUOUS → 未归属桶**（记录冲突证据） |
| C7 | 无任何上下文 | UNATTRIBUTED，不猜 |
| C8 | 伪造不存在/无绑定的 project tag | 403 CONTEXT_NOT_ALLOWED；同 Key 的合法请求不受影响 |
| C9 | 一 Key 多绑定 | 各自上下文均正常 |
| C10 | 撤销 B 的 binding | A 继续可用、B 立即 403（快照刷新内） |
| C11 | SSE 流式 | Agent/Gateway 双向逐字节透传（断流/取消/长流） |
| C12 | Agent 宕机 | doctor 可诊断；直连回退路径可用（请求落 UNATTRIBUTED/后缀） |
| C13 | 证据审计 | 每笔归属可在 `request_context_evidence` 复算解释（"为什么 15:32 记 MiQi"） |
| C14 | 头剥离 | 上游看不到任何 `X-Miqro-*`；伪造头被 Agent/Gateway 双重剥离 |

---

## 10. 开放确认项（开工前需要拍板）

| # | 事项 | 建议 |
|---|---|---|
| ① | C5/C6 阶梯：URL（最新用户轮次）vs cwd 的优先级 | 同级冲突=AMBIGUOUS；建议 URL 属"更鲜意图"高半级——请确认 |
| ② | PR #615 解冻作为 Binding/Grant 层起点（§0.3） | 建议解冻并叠加本 Spec 的头解析；若走 B 线（用户×项目授权）则 §6/§7 相应简化 |
| ③ | 未归属桶=租户级系统项目（§7.3） | 建议采纳（报表/重分类/审计都最顺） |
| ④ | v1 范围：是否含 hooks 增强与 apiKeyHelper 轻量模式 | 建议 v1 纯 Agent（§0.2 M2/M4），其余 v2 |

## 11. 分阶段实施（对齐提案 Phase 1–5）

| Phase | 内容 | 仓库落点 |
|---|---|---|
| P1 | Context 数据模型（Evidence/Resolution/Status/Confidence） | `miqro-context/src/resolver|state` |
| P2 | Collector（body 三件套：system_cwd / tool_path / prompt_url + git 解析） | `miqro-context/src/collector` |
| P3 | Local Agent（localhost 端点 + 注入 + SSE 透传 + install/doctor） | `miqro-context/src/proxy|cli` |
| P4 | Gateway（RequestContextResolver + 失败语义 + 管线 + 头剥离） | `gateway-app`（叠 #615） |
| P5 | Usage/Billing（V54/V55 + 未归属桶 + registry API + 审计/重分类） | `control-plane` + `route-snapshot` + `domain` |

每 Phase 出口 = §9 对应测试用例自动化通过 + 文档同步（api-contract/database-schema/progress）。
