# MiQroKey Context Attribution Architecture —— 实现级 Spec v1.1（AI 可直接开工）

- 日期：2026-09-16
- 状态：**v1.1 实现规格（吸收评审 6 项必修 + 2 项补强后成稿；剩余 3 个开放项见 §10，均为配置级、不阻塞开工）**
- 前置文档：`docs/activity-context-design.md`（设计稿，issue #629）；本文为 v1 的修订版（v1.1，修订记录见 §0）
- 命名：**MiQroKey Context Attribution Architecture（CAA）**

## 0. 修订记录（v1 → v1.1，逐条对应评审意见）

| # | 评审项 | 级别 | v1.1 处理 |
|---|---|---|---|
| R1 | 全量 body 历史 ≠ 当前证据（历史污染） | **P0** | 引入 **TurnEvidence**（会话水位线差分 + 证据作用域：当前轮 > 会话历史）；证据不再"只增不减"（§3.2、§5） |
| R2 | UNATTRIBUTED 不得借用某个项目的 primary grant/凭证 | **P0** | 归属未知一律走**独立的租户级 `unattributed_policy`**（独立凭证/模型范围）；未配置该策略时多绑定 Key 直接 `CONTEXT_REQUIRED`，单绑定 Key 天然可归属（§4、§6.3、§7.3） |
| R3 | 新架构核心标识符改为 project_id，tag 仅兼容 | P1 | 头改为 **`X-Miqro-Project-Id: <uuid>`**；后缀路径 = tag → project 的 **legacy 映射**；binding 查找按 (keyId, projectId)（§3.3、§4、§6） |
| R4 | Activity 切换条件 = resolved context 稳定变化（非证据字段变化），加滞回 | P1 | 段边界仅在**解析项目变化**时发生；HIGH 即时切换、仅 MEDIUM 需连续两轮一致；同项目内文件/路径变化不再开段（§3.2） |
| R5 | Agent 的 source/confidence 是"声明"，Gateway 不得当事实 | P1 | 数据模型分列 **claimed_*（Agent 声明）** 与 **resolved/ resolution_status（服务端裁定）**；v1 Agent claim = untrusted hint（§7.1、§6.3） |
| R6 | 删除"URL 比 cwd 高半级"的打分 | P1 | 改为**作用域 + 分组 + 冲突模型**（current-turn scope → group by project → 多个独立 HIGH 组=AMBIGUOUS → 唯一主导=RESOLVED → 否则 UNATTRIBUTED），不用标量排序（§5.3） |
| R7 | session_id 不得假设一定有 | 补强 | `session_id` 可空、纯观测元数据，永不参与路由/授权（§7.1） |
| R8 | "fail-open" 措辞失真 | 补强 | 更名 **Agent Failure / Direct Gateway Degraded（手动直连降级）**；并明确 Agent 不是安全边界，Gateway 才是（§3.4、§9 C14） |

另采纳评审两处措辞升级：第三句原则改为 **"One Request, One Deterministic Attribution Decision"**（AMBIGUOUS/UNATTRIBUTED 也是一次确定性决策）；新增 **`AttributionDecision`** 一等对象（含 `evidenceIds[]`、`decidedAt`）。

---

## 1. 总纲

### 1.1 四句原则

1. **One Key, One Identity** —— CC Switch 永远一条配置、一把 Key；Key 只回答"我是谁"。
2. **One Session, Many Activities** —— 项目随意穿插；归属是请求级。
3. **One Request, One Deterministic Attribution Decision** —— 每个请求产生一个确定性的归因决策（RESOLVED / AMBIGUOUS / UNATTRIBUTED 三者都算决策，均带证据引用）。
4. **When Evidence Is Insufficient, Do Not Guess** —— 证据不足/冲突 → 不猜；进未归属桶或按租户策略路由。

分层：`Identity（Virtual Key）→ Activity Context（每请求决策）→ Authorization（Binding/Grant）→ Usage（Billing）`。与 AWS Bedrock"稳定身份/工作负载维度 + per-request metadata（由共享客户端/网关统一附加）"分层同构。

### 1.2 与既有 PR 的关系（和解）

本架构的授权链 = `virtual_key + project_id → binding → grant`，验收 C9/C10 要求"一 Key 多项目授权 / 撤销某项目 binding 其余不受影响"——即 **PR #615 已实现的绑定引擎**。处置：**建议解冻 #615 作为授权层起点**，选择器改为"project-id 头优先、后缀 tag 兜底"（§4）。若改走"用户×项目"授权（设计稿 §6-B），§6/§7 按该线简化。**本 Spec 默认按 Binding 线编写**（§10-①）。

---

## 2. 最终用户体验（同 v1，不变）

CC Switch 唯一一条配置（API 请求地址指向本机 Agent `http://127.0.0.1:8788`）；Session 内多项目穿插；Key/CC Switch/Session 全程不动；用量按请求归属，`MiQi 180k / MiqroForge 90k / quant 70k / …`；无证据的请求落"未归属"。

---

## 3. 客户端：miqro-context（TypeScript / Node ≥ 20）

### 3.1 目录结构（v1.1 增 delta 模块）

```
miqro-context/
├── src/
│   ├── collector/
│   │   ├── delta.ts         # ★ 会话水位线：计算"本轮新增消息"（TurnDelta）
│   │   ├── body.ts          # 从 TurnDelta 提取证据（system env / tool_use / URLs）
│   │   ├── git.ts           # cwd → git root/remote（本地只读）
│   │   └── url.ts           # github.com/{owner}/{repo}/(pull|issues)/{n} → repo key
│   ├── resolver/
│   │   ├── evidence.ts      # Evidence / TurnEvidence / 置信度
│   │   ├── decision.ts      # AttributionDecision 组装
│   │   ├── resolver.ts      # 作用域+分组+冲突模型（§5.3）+ 段切换滞回（§5.4）
│   │   └── registry.ts      # repo → project_id 映射缓存（服务端同步）
│   ├── state/
│   │   ├── session.ts       # sessionKey → ConversationWatermark / 当前会话上下文
│   │   └── activity.ts      # ActivitySegment（仅在 resolved 项目稳定变化时开新段）
│   ├── proxy/
│   │   ├── server.ts        # 仅监听 127.0.0.1
│   │   ├── forward.ts       # undici 转发；SSE 逐块透传；abort 双向传播；body 逐字节不改
│   │   └── inject.ts        # 注入/剥离 X-Miqro-* 头
│   └── cli/{install,status,doctor}.ts
└── package.json
```

### 3.2 状态机（v1.1 重写：TurnDelta + 滞回）

```
请求到达
   │
   ▼
TurnDelta = 会话水位线之后的"新增消息"（首请求=全量即 delta）
   │
   ▼
TurnEvidence = 从 TurnDelta 提取的证据（URL / tool_path / bash_cwd / system_cwd）
   │
   ▼
归因决策（§5.3）：
   TurnEvidence 有项目信号 → 本轮直接按证据归属（HIGH 即时；MEDIUM 亦即时但见下）
   TurnEvidence 无项目信号 → 沿用"会话当前上下文"（上次稳定解析）
   全无 → UNATTRIBUTED
   │
   ▼
会话上下文 / ActivitySegment 更新（§5.4 滞回）：
   解析项目未变 → 不动作（同项目内文件/路径变化不产生任何段变化）
   变为 B：≥1 条 HIGH → 立即切换；仅 MEDIUM → 需连续两轮一致才切换
   切换即开新 ActivitySegment（activityId=uuid）
```

**核心不变式（R1）**：历史轮次的消息**永不**作为当前轮的归属证据——旧轮证据只能通过"会话当前上下文"这一已被滞回确认的**状态**间接参与（且仅在当前轮无信号时兜底）。因此：
`MiQi(工具历史仍在 body) → 用户问 MiqroForge PR` 的轮次 = **TurnEvidence 只有 MiqroForge** → 归属 MiqroForge，**不会**与旧 MiQi 构成冲突。

### 3.3 转发契约（Agent → Gateway）

- 透传方法/路径/查询/body/`Authorization` **逐字节**；剥离客户端伪造的 `X-Miqro-*` 头；注入：
  ```
  X-Miqro-Project-Id: <uuid>            # RESOLVED 时（opaque id；R3）
  X-Miqro-Activity: <uuid>              # 段内稳定
  X-Miqro-Claim-Source: prompt_url|tool_path|bash_cwd|system_cwd|git_remote|none
  X-Miqro-Claim-Confidence: HIGH|MEDIUM|LOW|NONE
  X-Miqro-Claim-Status: RESOLVED|AMBIGUOUS|UNATTRIBUTED
  ```
  **这些全部是"声明"（claim），不是权威**（R5）；AMBIGUOUS/UNATTRIBUTED 时不注入 Project-Id。
- 响应含 SSE **逐块透传**；上游错误原样返回；中断双向传播；Agent 不做鉴权/改写/重试。
- 头名说明：`X-Miqro-Project-Id` **在 CAA 主路径下可放心使用**——Claude Code 的"敏感词头名门控"只作用于它从 settings/env 读取的 `ANTHROPIC_CUSTOM_HEADERS`（静态头降级模式），**Agent 自行注入的请求头不受该门控影响**。仅当未来启用"无 Agent 静态头降级模式"时，该模式需另选不含 `project/key/…` 的头名（如 `X-Miqro-Target-Id`，已实证可通过门控）。

### 3.4 运行与安装（R8 措辞修订）

- 监听 127.0.0.1:8788（可配）；三平台自启；`miqro-context install/status/doctor`。
- **Agent Failure / Direct Gateway Degraded**：Agent 不可用 = 端点不可达，用户可按文档把 base_url 直连 Gateway（此后无上下文：单绑定 Key 照常、多绑定走策略或 CONTEXT_REQUIRED）——这是**手动直连降级模式**，不是"fail-open"。
- 隐私：证据只在本机处理；上行仅头部声明；debug 日志禁止正文。

---

## 4. 传输契约与校验阶梯（R2/R3）

```
X-Miqro-Project-Id 头（Agent 注入，声明）           ← 主路径
      │ 无
      ▼
密钥后缀 .tag（legacy Compatibility）→ tag → project_id 映射（projectTag 索引）
      │ 无
      ▼
Key 的绑定数：
   单绑定 → 该绑定（项目可确定；resolution=SOLE_BINDING）
   多绑定 → 租户配置了 unattributed_policy ？ 经策略路由，usage 落未归属桶
                                              ： 400 CONTEXT_REQUIRED
```

失败语义：

| 情形 | 响应 |
|---|---|
| 声明 project_id 无该 Key 的 ACTIVE binding | **403 CONTEXT_NOT_ALLOWED**（可调试；404 防枚举只留给密钥本身无效） |
| 声明格式非法 | 400 CONTEXT_INVALID |
| 多绑定 + 无上下文 + 无策略 | 400 CONTEXT_REQUIRED |
| Claim-Status=AMBIGUOUS/UNATTRIBUTED（或解析后无项目） | 不拒绝：按 §6.3 走 **unattributed_policy**（未配置则 CONTEXT_REQUIRED），usage 落未归属桶并记声明 |

**权威声明（R5/R8）**：Agent 及其声明都**不是安全边界**——真正的边界永远是 Gateway 的 `Key + Binding` 校验；`claimed_*` 全部按"未验证输入"入库。

---

## 5. Context Resolver（本地、确定性、禁止 LLM 猜测）

### 5.1 证据模型（v1.1）

```ts
type Evidence = {
  source: 'prompt_url' | 'tool_path' | 'bash_cwd' | 'system_cwd';
  value: string;                 // 规范化：repo key 或绝对路径
  repoKey?: string;              // 若可解析出 git remote
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  scope: 'turn' | 'session';     // ★ 作用域（R1）
  observedAt: number;
  requestId: string;
};

type AttributionDecision = {     // ★ 一等对象（R5/评审§16）
  status: 'RESOLVED' | 'AMBIGUOUS' | 'UNATTRIBUTED';
  projectId?: string;
  activityId?: string;
  claimSource: Evidence['source'] | 'none';
  claimConfidence: 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';
  evidenceIds: string[];
  decidedAt: number;
};
```

### 5.2 采集（仅作用于 TurnDelta；置信度表）

| 来源 | 提取 | 置信度 |
|---|---|---|
| prompt_url | **本轮**新增用户消息中的 `github.com/{owner}/{repo}/(pull\|issues)/\d+` | HIGH |
| tool_path | **本轮**新增 tool_use 的 `file_path/notebook_path/path` → git root → remote | HIGH |
| bash_cwd | **本轮**新增 Bash `command` 中 `cd <path>` / 绝对路径 → git root → remote | MEDIUM |
| system_cwd | `Working directory` → git remote（是 git 仓库） | HIGH |
| system_cwd | `Working directory` 非 git 仓库 → 目录名 | MEDIUM |

（hooks 增强（标准 CLI 环境）：`CwdChanged` 等作为同表补充来源，v2。）

### 5.3 归因决策（R6：作用域+分组+冲突，无标量打分）

```
1) 取当前轮 TurnEvidence；
2) 按"证据解析出的项目"分组（repoKey → registry → projectId）；
3) 独立 HIGH 组 ≥ 2 → AMBIGUOUS（记录冲突双方的 evidenceIds）
   独立 HIGH 组 = 1 → RESOLVED（该项目）
   无 HIGH、有 MEDIUM 组 = 1 → RESOLVED；MEDIUM 组 ≥ 2 → AMBIGUOUS
4) TurnEvidence 无任何项目信号 → 沿用会话当前上下文（若存在）→ RESOLVED(继承)
5) 全会话也无 → UNATTRIBUTED
（registry 未命中任何 repoKey → UNATTRIBUTED；绝不用目录名硬猜项目）
```

**C5/C6 由此自然成立**：C5（cwd 无项目信号 + URL=B）= 单一组 → B；C6（cwd 的 git remote=A 为 HIGH + URL=B 为 HIGH）= 双 HIGH 组 → AMBIGUOUS。**不存在"URL +0.5"**。

### 5.4 ActivitySegment 切换（R4：稳定变化 + 滞回）

- 段边界**只在解析项目变化**时发生；同项目内证据字段变化（文件/路径/命令）**不动作**；
- 切换条件：新项目 ≥1 条 HIGH 证据 → 立即切换；仅 MEDIUM → 连续两轮一致才切换（第一轮记 pending，不切段）；
- 段内 `activityId` 稳定；A→B→A 且每次变化均有 HIGH 证据 → 如实产生 A/B/A 三段（真实发生的工作，不做人为合并）；flicker 仅可能来自弱证据，被 MEDIUM 二次确认规则挡住。

---

## 6. Gateway（Java；gateway-app + route-snapshot + domain）

### 6.1 类清单

| 类 | 动作 |
|---|---|
| `VirtualKeyResolver` | 保留（Identity 不动） |
| **`RequestContextResolver`（新增）** | 提取/剥离 `X-Miqro-*`；解析 `ContextClaim{claimedProjectId, activityId, claimSource, claimConfidence, claimStatus}`；产出 `ResolvedContext`（含 resolution_status：RESOLVED_HEADER / RESOLVED_SUFFIX / SOLE_BINDING / POLICY_ROUTED / CONTEXT_REQUIRED / CONTEXT_NOT_ALLOWED / CONTEXT_INVALID） |
| `RouteSnapshot.binding(keyId, projectId)` | **新增按项目 id 的绑定查找**（快照 bindings 同时保留 tag 键以支持 legacy） |
| `AuthContext` | + `resolvedContext`（含 claimed/resolved/status/source/confidence/activityId） |
| `ProxyController` / `ModelsController` | 门控用解析后 binding 的 grant（#615 已备）；管线插入 Context 提取/校验 |
| `UsageEvent` | + `claimedProjectId / resolvedProjectId(≡project_id) / resolutionStatus / claimSource / claimConfidence / sessionId / activityId` |

### 6.2 管线

```
HTTP → Credential Auth → Virtual Key Identity → Context Claim Extraction
     → Context Validation（binding 存在性 + 策略路由判定）
     → Binding/Grant（或 unattributed_policy）→ Provider Routing → Usage Event（含决策与证据引用）
```

### 6.3 归属未知时的路由（R2）

- **单绑定 Key**：项目天然确定（SOLE_BINDING）→ 正常路由与记账，不受上下文噪声影响；
- **多绑定 Key + 无法归属（无上下文 / AMBIGUOUS / UNATTRIBUTED）**：
  - 租户配置了 **`unattributed_policy`** → 以该策略的（凭证、产品、模型范围）路由，usage 记入**未归属桶**（project_id=UNATTRIBUTED 系统项目）+ 保留 claimed 声明与证据；
  - 未配置 → **400 CONTEXT_REQUIRED**（宁可暴露，不偷用任何项目的资源）。
- **任何情况下不得**借用某个具体项目的 grant/凭证来执行"无法归属"的请求。

---

## 7. 数据库（追加迁移）

### 7.1 V54 `usage_event` 增列（全可空；claimed 与 resolved 分离，R5/R7）

```
session_id             varchar(64)   -- 观测元数据，可空，不参与路由/授权
activity_id            uuid
claimed_project_id     uuid          -- Agent/后缀"声明"的项目（未验证输入）
resolution_status      varchar(32)   -- RESOLVED_HEADER|RESOLVED_SUFFIX|SOLE_BINDING|POLICY_ROUTED|UNATTRIBUTED|AMBIGUOUS
claim_source           varchar(32)   -- prompt_url|tool_path|bash_cwd|system_cwd|suffix|none
claim_confidence       varchar(16)   -- HIGH|MEDIUM|LOW|NONE（声明，非事实）
project_id             （既有列）    -- = 服务端裁定后的归属（未归属桶时为系统项目 id）
```

### 7.2 V55 `request_context_evidence`（同 v1；审计"为什么这么判"）

```
request_id / source / value / confidence / observed_at / scope(turn|session)
索引 (tenant_id, request_id)
```

### 7.3 未归属桶 与 租户策略（R2）

- 每租户系统项目 **`UNATTRIBUTED`**（不可删除、不可被正常建 Key 选择）；
- 新表 **`unattributed_policy`**（每租户至多一行）：
  ```
  tenant_id uuid PK
  credential_id uuid FK        -- 独立凭证（不隶属于任何项目 grant）
  provider_product_id uuid FK
  model_scope jsonb            -- 模型范围
  updated_by/updated_at
  ```
  管理端「全局配置」页配置；**策略的凭证与项目凭证物理分离**，杜绝"偷用项目资源"。

### 7.4 Project Registry

```
project_repositories (id, tenant_id, project_id FK, repo_key, created_at, updated_at)
唯一 (tenant_id, repo_key)；repo_key = 规范化 github.com/{owner}/{repo}
```

---

## 8. API 增量

| 端点 | 用途 |
|---|---|
| `GET /api/v1/me/context-registry` | Agent 同步 **repo → project_id** 映射（仅该用户可见项目；ETag 增量） |
| `POST/DELETE /api/v1/admin/projects/{id}/repositories` | 维护仓库映射 |
| `PUT /api/v1/admin/unattributed-policy` / `GET` | 配置未归属策略（凭证/产品/模型范围） |
| `GET /api/v1/admin/usage/attribution?status=UNATTRIBUTED\|AMBIGUOUS` | 查未归属/歧义请求（join 证据明细） |
| `POST /api/v1/admin/usage/attribution/reassign` | 事后重分类（审计、幂等） |

---

## 9. 测试矩阵（v1.1 增补）

| # | 场景 | 期望 |
|---|---|---|
| C1–C4 | 单项目 / A→B→A / A 代码→B PR / A 代码→B 文档→C 流程图 | 逐请求归属正确（对照 v1） |
| C5 | cwd 无项目信号 + 本轮 URL=B | RESOLVED B（单一证据组） |
| C6 | cwd 的 git remote=A（HIGH）+ 本轮 URL=B（HIGH） | **AMBIGUOUS**（双 HIGH 组）→ 策略路由/未归属桶 |
| C7 | 全无证据 | UNATTRIBUTED |
| **C15（R1 回归）** | MiQi 工具历史仍在 body，本轮 URL=MiqroForge | 归属 MiqroForge；**不得**出现与旧 MiQi 的 AMBIGUOUS；旧证据不入本轮 TurnEvidence |
| **C16（R4 回归）** | 同项目内连续多轮不同文件/路径 | 不新开 ActivitySegment；仅 MEDIUM 的 A→B 单轮不切段、连续两轮才切 |
| C8 | 伪造/无绑定 project_id | 403 CONTEXT_NOT_ALLOWED；同 Key 合法请求不受影响 |
| C9/C10 | 一 Key 多绑定 / 撤销 B 后 | 正常；A 可用、B 立即 403 |
| **C17（R2 回归）** | 无法归属的请求 | 只走 `unattributed_policy` 的独立凭证；**断言未使用任何项目 grant/凭证**；未配策略 → 400 CONTEXT_REQUIRED |
| **C18（R2 回归）** | 单绑定 Key 无上下文 | 正常执行且归其唯一项目（SOLE_BINDING） |
| C11 | SSE 流式 | 双向逐字节透传（断流/取消/长流） |
| **C12（R8 更名）** | Agent Failure / Direct Gateway Degraded | doctor 可诊断；手动直连降级可工作（无上下文语义按 §4） |
| C13 | 证据审计 | 每笔归属可经 `request_context_evidence` + claimed/resolved 列复算解释 |
| **C14（R8 修订）** | 头剥离与声明不信任 | 上游看不到 `X-Miqro-*`；Agent 声明仅作 hint——**安全断言以 Gateway binding 校验为准**（Agent 非边界） |

---

## 10. 开放项（配置级，不阻塞开工）

| # | 事项 | 建议 |
|---|---|---|
| ① | PR #615 解冻作为 Binding/Grant 层起点（§1.2），还是走"用户×项目"B 线 | 建议解冻叠加（本文默认） |
| ② | `unattributed_policy` 的凭证来源：专用小额度凭证 vs 指定某项目凭证（不推荐） | 建议**专用凭证**（与项目资源物理分离） |
| ③ | v1 范围：是否含 hooks 增强 | 建议 v1 纯 Agent（body delta），hooks 留 v2 |

## 11. 分阶段实施

| Phase | 内容 | 落点 |
|---|---|---|
| P1 | Context 数据模型（Evidence/TurnDelta/AttributionDecision/Status/Confidence） | miqro-context |
| P2 | Collector（delta 水位线 + 四类证据 + git 解析） | miqro-context |
| P3 | Local Agent（localhost 端点 + claim 注入 + SSE 透传 + install/doctor/degraded 文档） | miqro-context |
| P4 | Gateway（RequestContextResolver + 校验阶梯 + 策略路由 + 头剥离 + 管线） | gateway-app（叠 #615） |
| P5 | Usage/Billing（V54/V55 + UNATTRIBUTED 项目与策略 + registry/重分类 API） | control-plane + route-snapshot + domain |

每 Phase 出口 = §9 对应用例自动化通过 + 文档同步。
