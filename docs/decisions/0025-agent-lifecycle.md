# ADR-0025：Agent 生命周期补齐——删除 / 重新启用 / 改名

- 状态：**Accepted（2026-09-19 所有者拍板，记录见 §7）**——采纳**选项 D**（`enable` + 改名/描述 + 硬删除）；实现跟踪见 issue [#1012](https://github.com/sijie-Z/miqro-gate/issues/1012)。
- 日期：2026-09-19
- 关联：issue [#824](https://github.com/sijie-Z/miqro-gate/issues/824)（四问出处）；[#714](https://github.com/sijie-Z/miqro-gate/issues/714)（凭证—Agent 绑定的不可变约束，本提案的关键交互方）；[#795](https://github.com/sijie-Z/miqro-gate/issues/795)（真机验收时发现演示站留下僵尸 Agent）；[ADR-0018](0018-single-key-multi-project.md)（资源命名与唯一性的先例）；feature-backlog（无对应条目——本提案不新增能力面，只补齐既有资源的生命周期）
- 触发事件：部署线在 #795 真机验收时需要一个临时 Agent，验证完只能把它永久留在演示站（停用态）——因为 API 面没有删除。同日核对该面时确认：**建错回不去、停用不可逆、被停用的 Agent 仍占着凭证名额**。

---

## 1. 现状（可核实；坐标取自 develop）

| 事实 | 坐标 |
|---|---|
| API 面只有 `list / get / create / disable / usage` | `AdminAgentController.java:40,45,50,57,64`（无 `@DeleteMapping` / `@PutMapping` / `enable`） |
| 状态枚举只有两个值 | `V17__agents.sql:14-16`（`CHECK (status IN ('ACTIVE','DISABLED'))`）；`Agent.java:13-16`（record 构造器校验同一枚举） |
| 乐观锁字段已具备 | `V17__agents.sql:17`（`version bigint NOT NULL DEFAULT 0`）、`Agent.java:12` |
| 名称在租户内唯一 | `V17__agents.sql:22`（`uq_agents_tenant_name`） |
| **一个凭证最多绑一个 Agent（不分状态）** | `V17__agents.sql:24-26`（`uq_agents_tenant_credential`，唯一索引，**无状态条件**） |
| `list()` 不做状态过滤（停用的也返回） | `AdminAgentService.java:52-54` |
| 凭证侧的锁**只对 ACTIVE 生效** | `AdminCredentialService.java:355-361`（javadoc「Disabling the agent releases the reference」）、`AgentRepositoryImpl.java:68-76`（`AND status = 'ACTIVE'`） |
| 该锁的调用点＝凭证轮换与凭证停用 | `AdminCredentialService.java:266`（轮换）、`:309`（停用）；冲突码 `CREDENTIAL_REFERENCED_BY_AGENT` |
| 凭证没有删除端点（FK `ON DELETE RESTRICT` 只在库级可达） | `AdminCredential*Controller` 下 `@DeleteMapping` 零命中；`V17:9-10` 的 FK |
| **删除在本控制面是既有惯例，Agent 面是异类** | 控制面已有 10+ 个资源级 `@DeleteMapping`：配额规则（`AdminQuotaRuleController.java:52`）、授权（`AdminGrantController.java:56`）、告警规则（`AdminAlertRuleController.java:66`）、MCP 路由规则（`AdminMcpRouteRuleController.java:73`）、模型目录行（`AdminModelCatalogController.java:100`）、项目成员（`AdminProjectController.java:62`）、团队成员（`AdminTeamController.java:62`）等 |
| 审计动作已有两个 | `AdminAgentService.java:83`（`AGENT_CREATE`）、`:95`（`AGENT_DISABLE`）；审计表是**append-only 哈希链**（`V3__audit_chain_position.sql` 头注） |
| 没有表引用 `agents` | 迁移中 `git grep "REFERENCES agents"` **零命中** |
| 没有任何表按 Agent 维度记录用量 | 迁移中 `agent_id` 零命中；`Agent.java:8-11` javadoc：用量按**绑定的凭证**聚合出每 Agent 视图 |
| 前端只有创建与停用 | `frontend/src/api/index.ts:675,683`；`frontend/src/views/next/NextAdminAgentsView.vue`（创建表单 + 行内停用） |

### 1.1 对 issue #824 一处转述的更正（先纠正事实，再谈选项）

issue 正文写「只要 Agent 存在（哪怕是停用态），其引用的凭证就不能轮换/停用（#795 的 409 是对的）」——**对当前 develop 不成立**：锁的查询带 `status = 'ACTIVE'`，**停用即释放**凭证（坐标见上表）。停用后凭证可以正常轮换与停用。

但由此暴露的真实约束是另一条、而且更硬：`uq_agents_tenant_credential` **不看状态** ——

- 停用的 Agent 仍占着「该凭证 → 唯一 Agent」的**名额**，想在同一凭证上**重新建一个 Agent** 会被 `AGENT_CREDENTIAL_TAKEN`（`AdminAgentService.java:73`）挡住；
- 想给凭证换一个 Agent（改绑）同样做不到，因为只能新建，而新建就被上一个（可能早停用的）行占位。

**这才是「僵尸 Agent」的真实形状**：不是凭证被锁死，而是**名额被占死**，且没有任何 API 能清掉它。§3 的选项评估以此为据。

---

## 2. issue #824 四问的逐条分析

### Q1 Agent 是否可删除？

**可删除，且数据层是干净的**：没有任何表以 FK 或列引用 `agents`（上表末两行）；用量不落 Agent 维度，所以删除**不影响**任何历史统计与配额。删除会同时释放两样东西：租户内的名称、以及 `uq_agents_tenant_credential` 上的名额。

代价与风险：

- **不可逆**（真删除）——需要二次确认；
- **审计可读性**：审计链按 `resource_id`（UUID）记录（`AdminAgentService.java:83,95`），行删掉后**按 id 反查不到名字**。因此删除时审计 detail 必须带**名称快照**，否则「谁在什么时候删了哪个 Agent」在审计里只剩一个 UUID；
- 与 #714 的交互：只允许删除，不允许在 ACTIVE 时直接删除吗？——建议**允许**（删除是比停用更强的动作，且凭证锁只对 ACTIVE 生效；删除 ACTIVE Agent 等于「停用 + 删除」一步到位），但必须在 UI 上把这一层讲清。

### Q2 Agent 是否可重新启用？

**可以，但它不是 `disable` 的简单逆操作**——这是本 ADR 的第二处事实发现：

`disable` 之后、`enable` 之前，**凭证可能已经变了**（停用后凭证即可轮换/停用，见 §1）。此时若直接 `ACTIVE` 化：

- 凭证已被**停用** → Agent 指向一个不可用的出口；
- 凭证已**轮换**（密文换了新版本）→ Agent 仍然可用（Agent 绑定的是凭证行，不是密文），这一点没问题；
- 凭证行**不存在**——不可能，FK 是 `ON DELETE RESTRICT` 且没有删除端点（上表）。

所以 `enable` 必须是**带前置校验的有条件逆操作**：凭证不存在或非 ACTIVE 时返回明确冲突（如 `CREDENTIAL_NOT_ACTIVE`，提示「先启用凭证或改绑」），而不是静默让 Agent 回到 ACTIVE。

### Q3 是否允许改名/改描述？

**建议允许**，理由是约束边界：

- #714 锁的是「被引用的**凭证与技能快照**」（`AdminCredentialService.java:355-361` 的 javadoc 只覆盖凭证侧），**Agent 自身的元数据不在约束范围内**；
- `agents` 表已有 `version` 乐观锁（`V17:17`）与 `updated_at`，更新路径的机制是现成的；
- 唯一性约束只要求改名后仍满足 `uq_agents_tenant_name`（同 `AGENT_NAME_TAKEN` 的既有语义）。

风险与缓解：**审计可读性**——改名后，历史审计行（按 id 引用）在展示层会跟着显示新名。缓解：`AGENT_UPDATE` 审计记**前后值**；若要更强的可追溯，可另开「名称变更历史」表（不建议，超出本提案）。

### Q4 若决定「有意不可删」，文档义务

issue 已经写到点子上：需要**写明理由与替代路径**（例如「停用即等价于删除」），否则下一个人会把它当 bug 反复提。若所有者选 A（维持现状），本 ADR 就承担这份文档：把「有意不可删 + 运维兜底＝直接改库」写进 `docs/operations-runbook.md`，并把 #824 关闭为「已决策」。

---

## 3. 选项与代价（含推荐，最终取舍属所有者）

| 选项 | 改动面 | 解决什么 | 代价 / 风险 | 默认行为 |
|---|---|---|---|---|
| **A. 维持现状 + 文档** | 仅文档（runbook 写明「停用即等价删除」与改库兜底） | 让口径可查，止住重复提问 | 僵尸行仍在；凭证名额占死无解；演示站已有实例 | 不变 |
| **B. 只补 `enable` + 改名/改描述** | 后端 2 个端点 + 前端行操作 + 审计动作 2 个（`AGENT_ENABLE` / `AGENT_UPDATE`）+ 测试 | 停用不再单向；建错名字可修 | **不解决名额占死**（Q1 未动）；`enable` 需凭证前置校验（§2 Q2） | 不变（新增能力，默认可用） |
| **C. B + 软删除**（`status='DELETED'`，行保留） | 迁移：CHECK 扩为三值 + `uq_agents_tenant_credential` 改**部分唯一索引**（`WHERE status <> 'DELETED'`）；列表默认过滤；`enable` 需拒绝 DELETED | 全部三问，且「已删除」在库里可追溯 | 三值状态渗透到所有读路径与前端徽章；索引改成分部唯一索引后，「同凭证多历史行」成为常态，`findActiveByCredentialId` 的「最多一行」注释要重写；删除语义变模糊（管理员以为删了，行还在） | 不变（新增能力） |
| **D. B + 硬删除（推荐）** | 后端 1 个端点 + 前端行操作（二次确认）+ `AGENT_DELETE` 审计（**detail 带名称快照**）+ 测试 | 全部三问，一次到位；名称与凭证名额都释放 | **不可逆**（用二次确认 + 审计兜底）；审计里该 Agent 此后只剩 UUID 与名称快照 | 不变（新增能力） |

**推荐：D。** 理由：

0. **一致性**：控制面已经有 10+ 个资源级删除端点（配额规则、授权、告警规则、项目/团队成员……见 §1 表），Agent 面是**异类**——「没有删除」在这个控制台里不是一条设计原则，而是一处缺口；
1. **没有任何东西引用 `agents`**（§1 末两行）——这是硬删除在本仓库可以「干净」的前提，换了别的资源（如凭证被 Agent 引用）都不成立；
2. **软删除的唯一收益是可见性**，而本仓库的审计链（append-only 哈希链）已经在另一条通道上覆盖了「谁在何时删了什么」——前提是删除时写入名称快照（本 ADR 把它写成硬要求）；
3. **僵尸行的根因是名额占死**，只有真删除能解；软删除还要额外改唯一索引、把 `findActiveByCredentialId` 的「最多一行」前提推翻，改动面反而更大、更危险；
4. A 与 B 都没有解决触发本议题的那个具体场景（#795 的临时 Agent 永远留在演示站）。

**触发条件（何时做）**：所有者拍板 D（或 C/B）即可做，无外部依赖；工程量按 §4 估计为小（后端两个端点 + 一次迁移都不是必需的：D 不需要迁移）。

---

## 4. 若采纳 D：落地形态（供拍板后细化）

| 面 | 内容 |
|---|---|
| API | `POST /api/v1/admin/agents/{id}/enable`（前置校验：凭证存在且 ACTIVE，否则 409）、`PATCH /api/v1/admin/agents/{id}`（改名/描述，带 `version` 乐观锁，冲突返回 409 `CONCURRENT_MODIFICATION`）、`DELETE /api/v1/admin/agents/{id}` |
| 审计 | `AGENT_ENABLE`、`AGENT_UPDATE`（前后值）、`AGENT_DELETE`（**含名称快照**，因为行删除后按 id 反查不到名字） |
| 前端 | 行操作补齐为「停用/启用 │ 改名 │ 删除」（遵循既有的行操作链接约定：蓝链 + 「更多」菜单，不用 ghost 按钮）；删除走二次确认弹窗，文案说明「不可恢复」 |
| 不做的 | 改绑凭证（见 §6-1）、按状态过滤列表（见 §6-5）、批量操作 |
| 测试 | 后端：三个端点的契约测试 + 「凭证非 ACTIVE 时 enable 被拒」+ 删除后名称与凭证名额可用（`AGENT_NAME_TAKEN` / `AGENT_CREDENTIAL_TAKEN` 都消失）；前端：行操作与确认弹窗 |

---

## 5. 后果

- **数据面**：无运行时行为变化（新端点默认存在但只有显式调用才生效）。删除是不可逆操作，UI 需二次确认。
- **管理面**：`agents` 面从「可建/可停」变为完整生命周期；演示站的僵尸行可按新路径清理（验证素材）。
- **文档**：`docs/operations-runbook.md` 补一节「Agent 的停用与删除区别」；`docs/api-contract.md` 增补三个端点。
- **历史与审计**：用量/配额不受影响（不落 Agent 维度）；审计链不受影响（append-only，行删除不触碰审计表）。

---

## 6. 未决项（需要所有者拍板或后续证据）

1. **改绑凭证**（Agent 换一个出口凭证）是否允许？本 ADR 不展开——它与 #714 的不可变约束正面相关，建议单独议题；
2. **`enable` 时凭证已停用**：拒绝（推荐，强制先修凭证）还是允许 ACTIVE 但标记降级？
3. **删除的权限粒度**：是否与 create/disable 同级（当前 admin 面统一权限）？
4. **二次确认形态**：普通确认弹窗，还是要求输入 Agent 名称确认（对不可逆操作的更强门槛）？
5. **列表默认过滤**：`list()` 现在不过滤状态（`AdminAgentService.java:52-54`）；是否默认只看 ACTIVE、用筛选器查看停用？
6. **`AGENT_DELETE` 的名称快照**：写进 `detail`（本 ADR 的硬要求），还是同时在前端审计视图做「已删除资源」的展示处理？

---

## 7. 事实来源

- 本仓坐标：`V17__agents.sql`、`Agent.java`、`AdminAgentController.java`、`AdminAgentService.java`、`AdminCredentialService.java`、`AgentRepositoryImpl.java`、`V3__audit_chain_position.sql`、`frontend/src/api/index.ts`、`frontend/src/views/next/NextAdminAgentsView.vue`（均取自 develop，逐条 `git show` 读取）
- issue：[#824](https://github.com/sijie-Z/miqro-gate/issues/824)（四问）、[#714](https://github.com/sijie-Z/miqro-gate/issues/714)（凭证锁）、[#795](https://github.com/sijie-Z/miqro-gate/issues/795)（僵尸 Agent 现场）
- 行业形态（**未复核，仅作参照、不作论据**）：AWS Bedrock Agents 支持 create/update/delete 与版本别名；阿里云百炼的智能体应用同样支持删除。若拍板时需要，另附来源。

---

## 7. 所有者拍板记录（2026-09-19，状态由 Proposed 转为 Accepted）

- 答复形式：所有者对评估线推荐的选项整体确认（「可以，做吧」）→ 采纳 **D**。
- 本记录不改变 §1 的现状描述；实现跟踪见 issue [#1012](https://github.com/sijie-Z/miqro-gate/issues/1012)。

| # | 决策 | 结论 |
|---|---|---|
| D1 | 四条路径 | **D**：`enable` + 改名/描述 + **硬删除**（二次确认 + `AGENT_DELETE` 审计带名称快照） |
| D2 | Q1 可删除 | 可（硬删除）。数据层无任何引用，用量按绑定凭证聚合、不受影响 |
| D3 | Q2 可重新启用 | 可，但为**有条件的逆操作**：凭证必须存在且 ACTIVE，否则 `409 CREDENTIAL_NOT_ACTIVE` |
| D4 | Q3 改名/改描述 | 可。乐观锁（`version` 随行返回、表单回传）+ 重名 409；审计记 before→after |
| D5 | Q4 若「有意不可删」 | N/A（未采纳该路径） |

### 7.1 §6 未决项的处置

| §6 项 | 处置 |
|---|---|
| 1 改绑凭证是否允许 | **不在本批**：与 #714 的不可变约束正面相关，作为单独议题（§6-1 原样保留） |
| 2 `enable` 时凭证已停用 | 采纳 §2-Q2 的推荐：**拒绝**并提示先启用凭证 |
| 3 删除的权限粒度 | 与 create/disable 同级（控制面 admin 面统一权限），不新增细粒度 |
| 4 二次确认形态 | 普通确认弹窗 + 文案说明「不可恢复、用量与对账不受影响」；「输入名称确认」的更重门槛留待 UI 评审 |
| 5 列表默认过滤 | **保持现状不过滤**（`list()` 全量返回 + 状态徽章），避免改变既有行为；删除落地后僵尸行问题本身消失 |
| 6 审计快照展示 | `AGENT_DELETE` 的 detail 带名称快照（硬要求）；前端审计视图对「已删除资源」的呈现留待后续 |

### 7.2 效力

- 本文件状态为 **Accepted**；§3 的选项 D 与 §7.1 的处置即为生效决策，实现按 §4 的落地形态执行。
- 未采纳的选项 A/B/C 保留在 §3 作为记录；若将来要偏离 D（软删除、放开改绑等），按新 ADR 或本文件修订处理。
