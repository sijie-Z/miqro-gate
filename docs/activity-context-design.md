# MiQroKey Activity Context —— 请求级归属设计（含真机实验证据与待决问题）

- 日期：2026-09-16
- 状态：**设计稿 v0.1（待评审——请 ChatGPT / leader 过目，问题清单见 §7）**
- 关系：PR #615（单密钥多项目·标签路由）已实现 **Identity→Project 的地基**；本文定义其上的更高层问题——**Identity ≠ Workload ≠ Request**，即"请求级归属（Request / Activity Attribution）"的完整方案。
- **实现级 Spec（AI 可直接开工）：[docs/context-attribution-implementation-spec.md](../context-attribution-implementation-spec.md)**
- 实验脚本与原始记录：`D:/tmp/ctx-hook.py`、`ctx-helper.py`、`ctx-fake-model.py`、`ctx-capture.log`（可复现）

---

## 1. 问题：为什么"一 Key 多项目"还不够

项目边界**根本不固定**。同一个人的同一个 session（甚至相邻两句话）可以：

```
Session X
├─ A 项目写代码
├─ B 项目审 PR
├─ C 项目写文档
├─ A 项目做测试
└─ D 项目画流程图
```

因此：

- `Session → Project` 太粗（一个 session 干多项目）；
- `Key + Tag → Project` 同样太粗——如果 tag 由"人"来选（切条目/改后缀），又回到了"让用户自己切"的老路。

**正确的抽象层级是 Request / Activity**：每个请求（每次模型调用）单独归属，而不是 Key 或 Session 归属。

**同时必须承认信息论边界**：如果一个请求里不携带任何项目信息，任何系统都不可能"知道"它属于哪个项目。所以唯一真问题是：**项目上下文从哪里来、如何无感地注入每一个请求。**

## 2. 终态模型（四层）

```
┌────────────────────────────┐
│ Identity      mqk_live_xxx │  “我是谁”     —— 一个永久 Key，永不更换
├────────────────────────────┤
│ Activity      当前在干什么  │  “这一刀在给谁干活” —— 每请求携带
├────────────────────────────┤
│ Project       归属项目/资源  │  “记到哪个项目的账上”
├────────────────────────────┤
│ Grant         模型/凭证/权限 │  “我在这个项目里被允许用什么”
└────────────────────────────┘
                ↓
Usage Event { user_id, session_id, project_id, activity?, model,
              input_tokens, output_tokens, cost, ts, attribution_source }
```

三个原则：

1. **Key 只承载身份**，不承载项目（后缀降级为 fallback / 显式兼容）；
2. **项目上下文自动注入**（由工作区/客户端产生，用户零动作）；
3. **Gateway 只做验证与授权**（"你声称是 MiQi？我查这把 Key 有没有 MiQi 的 binding"），**永远不猜**。

## 3. 大厂对标（方向正确性的外部校验）

| 厂商 | 机制 | 启示 |
|---|---|---|
| **AWS Bedrock** | 身份归因（IAM principal / session tags）、Project / Workspace、Inference Profile、**per-request metadata** 四级并存；官方明确建议"要按每个 prompt 做 token 级归因 → 用 per-request metadata；session tag 适合稳定的 session/user 维度" | Identity ≠ Workload ≠ Request，分开建模 |
| **Google Cloud** | Project 是资源/权限/成本的基础组织单位，配 labels 做成本分析 | 归属是显式的标签体系 |
| **GitHub Copilot** | 用户/组织/企业一个维度，agentic infra 成本可继续归到 repository | 请求级、多维度归因已是行业常态 |
| **Datadog 等** | usage attribution 建立在 instrumentation / tags 上 | 计费数据必须确定性、可审计 |

**没有任何一家用"读 prompt 猜项目"。** 我们的任务是：把这套"显式标签体系"在 Claude Code / CC Switch / 本地环境里做到**自动注入**。

## 4. 我们怎么做（三层设计）

### 4.1 传输契约（Gateway 侧——与 PR #615 地基完全复用）

- 每请求携带 `X-Miqro-Tag: <project-tag>` 头（**头名已按 Claude Code 官方"敏感词门控"逐 token 核过**：不能含 `project/key/user/org/token/host/endpoint/…` 等词，`X-Miqro-Tag` 可通过）；
- 解析顺序：**头部（工作区自动注入，优先）→ 密钥后缀（兼容回退）→ 单绑定兜底**；
- **多绑定 Key 且无任何上下文 → 400 fail-closed**——宁可报错，绝不静默记错账（财务口径底线）；
- 头声称了未绑定项目 → **403 明确报错**（调用方自知声称了什么，可调试；防枚举的 404 语义只保留给"密钥本身无效"）；
- 头**不参与 HMAC 鉴权、不转发上游**；绑定校验通过后，凭证/模型/用量链路与 #615 完全一致；
- `usage_event` 增记 `attribution_source`（HEADER / SUFFIX / FALLBACK / UNATTRIBUTED）与客户端自带的 `X-Claude-Code-Session-Id` → 审计可还原每笔归属的判定依据。

### 4.2 注入通道（客户端侧——本轮已做真机实验，证据见 §5）

| 通道 | 形态 | 特点 | 状态 |
|---|---|---|---|
| **A. 启动环境注入** | wrapper 脚本 / shell 环境设 `ANTHROPIC_CUSTOM_HEADERS` | 最简单、每请求生效 | ✅ **本机实测通过** |
| **B. 项目 settings** | 项目 `.claude/settings.json` 的 `env` | 随仓库分发、进目录即生效；需一次性信任；头名受敏感词门控 | ⏳ 本机未生效，待干净环境复核 |
| **C. 动态脚本** | `apiKeyHelper` 脚本输出 JSON `headers` | 官方**唯一动态通道**：脚本按 cwd/git 推导项目标签，所有项目零配置 | ⏳ 待干净环境复核 |
| **D. 证据采集** | `PostToolUse` hooks 记录结构化工具证据（文件路径 / 命令 / URL / cwd）→ 本地状态文件 → 由 C 注入 | 支撑"同 session 跨项目"的**请求级切换**；证据=结构化事实，非语义猜测 | ⏳ 待干净环境复核（链路先决条件已证，见 §5 实验 4） |
| **E. 企业下发** | managed settings（policySettings）统一下发 helper/头配置 | 不受项目级限制，适合上云/全员 | 设计路径，未实验 |

### 4.3 证据融合（本地 Context Resolver，绝不猜语义）

在**用户本机**（而不是网关）运行一个轻量解析器：

```
证据（全部是结构化事实，不是 prompt 语义）
├─ cwd                     D:\work\quant_platform
├─ git remote              github.com/…/MiQi
├─ Read/Edit 的文件路径     D:\work\MiQi\src\foo.ts
├─ Bash 命令中的 cd/路径    cd D:\work\quant_platform && pytest
├─ PR / issue URL          github.com/…/MiqroForge/pull/1083
└─ MCP / 工具目标           workspace / repository / ticket

          ↓ 规则表（路径前缀、git remote → 项目标签；可从仓库注册表自动生成）

   Context Resolver  →  X-Miqro-Tag: miqi（确定性映射）
```

- **高置信度（结构化证据命中规则表）→ 自动归属**；
- **无证据（如"帮我想个新的支付方案"）→ 不猜 → 标 UNATTRIBUTED（独立桶 / 待重分类）**；
- **不读 prompt 语义、不过分类模型**——与产品"网关不读正文"的红线一致；解析器跑在用户本机，看到的是本地已有的路径事实。

## 5. 真机实验记录（2026-09-16，本机真实 Claude Code + 自建抓包/假模型服务器）

| # | 实验 | 方法 | 结果 |
|---|---|---|---|
| 1 | **自定义头随每个请求到达** | shell env 设 `ANTHROPIC_CUSTOM_HEADERS="X-Miqro-Tag: miqi"` + 固定 Key，指向自建端点 | ✅ **通过**：真实请求 `POST /v1/messages` 同时携带 `Authorization: Bearer <固定Key>` 与 `X-Miqro-Tag: miqi`；另发现客户端自带 `X-Claude-Code-Session-Id`（可作审计维度） |
| 2 | settings 是否被加载 | `--settings` 文件里放 `model` 字段，看请求体 | ✅ 通过：请求体 `model=claude-sonnet-4-5` 生效 → **settings 加载链路正常** |
| 3 | settings 提供的 `env.ANTHROPIC_CUSTOM_HEADERS` / `hooks` / `apiKeyHelper` | 同一 settings 文件 | ❌ **本机未生效**（同一文件的 `model` 生效、这三项静默被弃）——与官方 changelog「敏感设置（凭据/路由/代码执行类）需审批」的机制吻合；疑为本机桌面版（claude-desktop-3p 环境）的额外限制。→ **待干净 CLI 环境 / managed settings 复核**（§7 Q1） |
| 4 | 假模型服务器驱动真实工具调用 | 自建 SSE 端点：第一轮返回 `tool_use(Read)` → Claude Code 真执行 Read → 第二轮带 `tool_result` | ✅ 通过：第二轮请求 `body_has_tool_result=true` —— **hooks 链路的先决条件（真实工具调用）成立** |
| 5 | hook 采集证据 → helper 注入 | 实验 3 的下游 | ⏳ 被 #3 阻断，随 #3 一并复核 |

实验结论：**"每请求自动携带项目上下文"的机制在真实链路上已被证实（实验 1）**；仅剩"用哪种配置通道让用户零配置地拿到它"需要在干净环境复核（#3）。

## 6. 授权粒度：Key×项目 vs 用户×项目（**Q0，最优先，决定 #615 的去留**）

产品负责人指出："后缀方案（#615 的产品面）没用"——对。而这句否定逼出一个此前被含糊过去的关键决策：**当 Key 退化为纯身份（用户级）之后，项目的访问权应该挂在哪一层？**

| | **A. Key×项目（#615 线，现状实现）** | **B. 用户×项目（终态线，建议）** |
|---|---|---|
| 授权载体 | 创建 Key 时逐项目绑定（binding 表；每绑定带 grant） | **人的项目成员关系 + 项目自身的授权（grant）**；请求头声明项目后校验"此人 ∈ 该项目" |
| 与"一人一钥"的一致性 | 弱——再用一把身份 Key 叠一层 per-key 项目授权，是"一 Key 一项目"时代的中间层残留 | 强——Key 就是人；项目权限是组织事实（成员/授权），天然随人变更 |
| 零切换贴合度 | 绑定要靠"建 Key 时勾选项目"维护，新增项目/调整权限都要动 Key | 入项目/退项目 = 既有成员管理；头部自动变动即生效，**零 Key 维护** |
| 成员移出项目 | 需按绑定行手术（#615 D7 特意实现的那套） | 成员关系本身即权限；一处生效，无需触碰任何 Key |
| 项目/标签改名 | 仍受"被绑定引用即不可改"约束 | 标签只被"引用解析"，守卫可退化为"改名需同步客户端规则表"的提示 |
| 限制"某把 Key 只能用某些项目"（代建/临时 Key） | 原生支持 | 默认不限制；如未来需要，作**可选收窄**（二期显式加 scope），不污染主模型 |
| 迁移成本 | 已实现（但产品面作废） | 网关侧更简单：header → 项目 → 成员/授权校验 → 项目 grant → 凭证/模型 → 用量 |

**建议：B（用户×项目）。** 理由：身份级 Key 下，per-key 的项目授权层是多余中间层——它服务的"按 Key 隔离项目"恰是后缀时代的需求；终态里项目访问 = 组织事实（谁是项目成员、项目有哪些授权），不应再经由 Key 中介。**若采纳 B，PR #615 应关闭**（salvage 评估：标签自动生成/守卫、成员移出语义中的普适部分并入 Activity Context 实现；binding 机制整体废弃）。**若保留 A**，则 #615 作为前置合入，头部解析叠加在 binding 校验之上（原方案）。

处置现状：#615 已 **Parked（暂缓合并，不关闭）**，等本 Q0 拍板后一次性处置。

## 6b. 与 PR #615 的关系

- 若 Q0=B（建议）：#615 的 binding 产品面废弃；实现按"header → 用户×项目授权 → 项目 grant"重建（比 binding 线更简单）；
- 若 Q0=A：**复用** #615 的 `(keyId, tag) → binding → grant → 凭证/模型 → 用量` 全链路；后缀降为 fallback；
- 两种情况下共同的**增量**：选择器扩展（`X-Miqro-Tag` 头部优先）+ `attribution_source`/`session_id` 落库 + 未归属策略 + 客户端参考实现（helper/hook 脚本）。

## 7. 待决问题（请评审 / 拍板）

| # | 问题 | 我的倾向 |
|---|---|---|
| **Q0** | **授权粒度**：Key×项目（现状 #615）vs **用户×项目**（建议）——决定 #615 去留与实现形态 | **用户×项目**（见 §6）；请 leader/ChatGPT 拍板 |
| **Q1** | 注入通道主推哪条？A（wrapper 环境）/ B（项目 settings）/ C（apiKeyHelper）/ D（hooks 证据）/ E（企业 managed）——需在干净 CLI 环境复核 3/5 号实验后定 | 演示用 **A+D 组合**；生产推 **C/D + 企业 managed 下发**（E） |
| **Q2** | 未归属桶策略：落到主绑定的项目 / 独立"未归属"虚拟项目 / 截停轻确认 | **独立"未归属"桶 + 支持事后重分类**（财务账纯净、可解释） |
| **Q3** | 是否引入 Activity 一级概念（task/activity_id），还是先只做 project 级 + attribution_source | 先 project 级 + source 记录；activity_id 预留字段、二期 |
| **Q4** | `session_id` 落库的隐私评估（X-Claude-Code-Session-Id 属元数据） | 建议落（审计价值大，无正文）——请复核 |
| **Q5** | 多绑定 Key 无上下文 → 400 fail-closed 是否接受 | **坚持**（不静默归属是财务底线） |
| **Q6** | "同 session 内真 per-turn 切换"的终局：等 Claude Code 开放请求钩子（官方 open feature request #21531）vs 本地代理读会话 transcript 兜底（涉及本机读会话文件，需评审隐私与稳定性） | 先交付"工作区锚定"形态；transcript 兜底作为可选增强，请评审 |

## 8. 下一步实施计划（问题澄清后立即开工）

1. **干净环境复核实验 3/5**（通道 B/C/D，一天内出结论）；
2. **网关增量**（小 PR，在 #615 合入之后）：`X-Miqro-Tag` 解析 + 失败语义 + `usage_event.attribution_source`/`session_id`（V54 迁移）+ 契约与测试；
3. **客户端参考实现**：`apiKeyHelper` + `PostToolUse` 证据脚本（含规则表模板），进「接入面板」供一键生成；
4. **演示闭环升级**：一把 Key + 两个项目目录各跑一次 Claude Code → 用量按"请求级项目"自动分账（全程零切换）。
