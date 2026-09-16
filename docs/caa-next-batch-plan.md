# CAA 收口批开发方案（Default-All 建 Key · 无归属策略 · Agent 自启）

- 日期：2026-09-16
- 状态：**方案稿（待外部评审）**——通过后按 §6 拆 issue/PR 实施
- 前置：CAA Spec v1.1（`docs/context-attribution-implementation-spec.md`）；已交付 P1–P4（#633/#639/#641）、每小时表（#634）
- 目的：把"一个人一把 Key、跨项目零切换、自动按项目计费"从**能跑**推进到**默认好用且兜底完整**

## 0. 现状与缺口

已具备（真机验证）：多绑定 Key（#615 绑定引擎）、逐请求归属阶梯（#633）、本机 Agent 自动证据与声明（#639）、registry 映射（#639/#641）、按项目的自动计费（沿用 grant/价格快照，零改动）。

缺口（本批解决）：

| # | 缺口 | 现状行为 | 目标 |
|---|---|---|---|
| ① | 建 Key 默认体验 | 需先选主项目、再手动勾附加项目 | **默认全选**用户全部可选项目（可取消勾选） |
| ② | 无法归属的请求 | 多绑定 Key 无上下文 → 一律 `400 CONTEXT_REQUIRED` | 租户可选配置**未归属策略**（专用凭证/产品/模型范围），路由并记账到**未归属桶**；未配置时维持 400 |
| ③ | Agent 部署 | 手动 `run`，关终端就断 | `install --autostart`：三平台登录自启 + `uninstall` |
| ④ |（观察项）| `/v1/models` 走完整阶梯，多绑定 Key 不匹配后缀 → 400 | 评估改 identity-only（同 #641 registry 的先例） |

## 1. ① 建 Key 默认全选项目（前端小改）

### 1.1 改动面

- `frontend/src/views/next/NextKeysView.vue`：
  - 附加项目多选（`extraProjectIds`）默认值 = `projectsForGrant` 中**除主项目外的全部**（即默认全选）；用户可取消；
  - 主项目下拉默认 = 可选项目列表第一项（现状可能为空，改为默认选中），消除"先手动选主项目"这一步；
  - 文案：多选标签下移一行说明"默认已选全部项目——一把 Key 全项目可用；如只需部分项目可取消勾选"。
- 无 API/DB 改动；`projectIds[0]` 仍为主项目（契约不变）。
- 边界：无可用项目（新用户未被加入任何项目）时维持现状空态引导；只有一个项目时多选区隐藏（现状）。

### 1.2 风险与测试

- 风险：用户误提交"全项目"Key（含暂时不想给的项目）→ 可接受：bindings 可随时由管理员禁用单项目绑定（#615 语义）；且多选可取消。
- 测试：NextKeysView spec（默认全选断言、取消勾选后提交参数、单项目时隐藏）+ 手工验证（演示站）。
- 验收清单新增"新拓扑主路径"（并入主清单执行）：
  1. 普通用户建 Key 表单打开 → 主项目已默认、其余项目已全选（截图级检查）；
  2. 直接提交（不调整任何项目）→ 创建成功，`boundProjects` 含全部项目；
  3. 两个项目目录（或两个项目标签）各发一次真实推理 → 用量报表按项目分开、每小时表按项目分行；
  4. 取消勾选一个项目再创建 → 该 Key 对该项目绑定缺失（声明该项目 → 403 CONTEXT_NOT_ALLOWED）。

## 2. ② 未归属策略（unattributed_policy）（Spec §7.3 落地）

### 2.1 目标语义（对齐 Spec v1.1）

- **触发**：多绑定 Key 且请求级上下文无法解析（无声明/无后缀命中/AMBIGUOUS）时：
  - 租户**未配置**策略 → 维持 `400 CONTEXT_REQUIRED`（现状，不偷用任何项目资源）；
  - 租户**已配置**策略 → 以策略的（凭证、产品、模型范围）路由，`usage_event.project_id` = **未归属桶项目**，`resolution_status = POLICY_ROUTED`，claimed_* 与证据照常落库。
- **不变量**：策略的凭证与项目 grant 的资源分离（见 §2.6 开放问题 Q1）；归属未知永不借用具体项目的 grant/凭证；单绑定 Key 不受影响（SOLE_BINDING 优先）。
- **计费**：桶项目参与既有成本/预算/告警/每小时表口径（它就是一行普通 project），管理员按需为其配预算。

### 2.2 数据模型（V57）

```sql
-- 共享表加列（additive）
ALTER TABLE projects ADD COLUMN system boolean NOT NULL DEFAULT false;
-- 约束：system=true 的项目不可被建 Key 选择（服务层 + 建 Key 校验），不参与常规项目选择器

CREATE TABLE unattributed_policy (
    tenant_id          uuid PRIMARY KEY REFERENCES tenants (id) ON DELETE RESTRICT,
    project_id         uuid NOT NULL,          -- 未归属桶项目（system=true）
    credential_id      uuid NOT NULL,
    provider_product_id uuid NOT NULL,
    model_scope        jsonb NOT NULL DEFAULT '[]'::jsonb,  -- 空数组 = 产品全部 ACTIVE 目录模型
    updated_by         uuid,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_uap_project FOREIGN KEY (tenant_id, project_id) REFERENCES projects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_uap_credential FOREIGN KEY (tenant_id, credential_id) REFERENCES upstream_credentials (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_uap_product FOREIGN KEY (tenant_id, provider_product_id) REFERENCES provider_products (tenant_id, id) ON DELETE RESTRICT
);
```

- 桶项目：首次 `PUT` 策略时懒创建（`code='UNATTRIBUTED'`, `name='未归属'`, `system=true`, tag=null）；`DELETE` 策略仅删除策略行（桶项目保留：历史用量引用它，FK 完好；`system=true` 保证不可被选）。
- `model_scope` 写入时逐项校验 `model_catalog`（与 grant 同规则 `MODEL_NOT_IN_CATALOG`）。

### 2.3 路由快照（route-snapshot）

- 装载 `unattributed_policy` → `Map<tenantId, UnattributedPolicyRecord(projectId, credentialId, productId, models)>`；快照记录中同时解析出**凭证的 baseUrl/authScheme**（复用 credentials map？策略凭证可能不在既有 credentials 装载范围——快照凭证装载来自哪些凭证？需核对：现有 credentials 装载含"被任一 grant 引用的凭证"还是全量 ACTIVE？**实施前核对装载 SQL**，策略凭证必须确保进入快照，否则路由取不到 baseUrl）。
- 控制面写策略后 `routeRefreshPublisher.publish()`（与 grant 同通道）。

### 2.4 网关解析阶梯终态（RequestContextResolver）

```
claim（绑定校验）→ 后缀命中 → 唯一绑定 →
    多绑定且以上全不中：
        tenant 有策略 → POLICY_ROUTED（binding=策略合成：projectId=桶、credential=策略凭证、product=策略产品、grant=null）
        无策略 → 400 CONTEXT_REQUIRED（现状）
    无绑定 → 404（现状）
```

- `ResolvedContext` 增合成路径：`binding` 为策略合成记录（grantId=null）。下游两处需处理 `grantId=null`：
  - `ProxyController` 模型门控：策略路径下 `allowed = 策略模型集 ∩ 上游目录模型（快照 upstreamModels(product)）`；**是否再与 Key 自身 `virtual_key_models` 求交 = 开放问题 Q2**；
  - `ProxyController` 凭证注入：经策略凭证走既有 `CredentialInjector`（credentialId 直取）；
  - usage 落库：`resolution_status='POLICY_ROUTED'`。
- `AuthContext.binding` 若为合成记录：`bindingCount/soleBinding` 等既有方法不受影响（合成记录不进快照）。

### 2.5 管理 API（control-plane）

| 端点 | 语义 |
|---|---|
| `GET /api/v1/admin/unattributed-policy` | 200 `{configured: bool, projectId?, providerProductId?, credentialId?, credentialName?, models[]?, updatedAt?}`（未配置也 200，UI 简单） |
| `PUT /api/v1/admin/unattributed-policy` | `{credentialId, providerProductId, models[]}`：校验凭证 ACTIVE 且其订阅产品==providerProductId（否则 `400 UNAUTH_CREDENTIAL_PRODUCT_MISMATCH`）、模型在目录（`MODEL_NOT_IN_CATALOG`）；懒建桶项目；upsert；审计 `UNATTRIBUTED_POLICY_SET`；刷新快照 |
| `DELETE /api/v1/admin/unattributed-policy` | 删除策略（桶项目保留）；审计 `UNATTRIBUTED_POLICY_CLEARED`；刷新快照 |

- **凭证复用告警**（Q1 倾向）：若所选凭证被任一项目 grant 引用，响应附 `warning: "该凭证同时被项目授权引用，建议使用专用凭证（Spec §7.3 物理分离）"`——**不硬阻断**，理由与开放问题见 §2.6。
- 建 Key 校验：`projectIds` 含 `system=true` 项目 → `400 PROJECT_NOT_SELECTABLE`（新错误码）。

### 2.6 前端（portal）

- 「设置」页新增卡片「未归属请求策略」：开关 + 凭证选择（复用既有凭证选择器）+ 产品（随凭证订阅联动）+ 模型多选（目录来源）+ 保存/清除；未配置时展示说明文案（"未配置时：多项目 Key 在无法判断项目时会拒绝请求（400）"）。
- 桶项目不出现在项目选择器/建 Key 流程（按 `system` 过滤）。

### 2.7 失败语义矩阵（新）

| 情形 | 响应 |
|---|---|
| 多绑定 + 无上下文 + 无策略 | `400 CONTEXT_REQUIRED`（不变） |
| 多绑定 + 无上下文 + 有策略 | 200，`POLICY_ROUTED`，usage 记桶项目 |
| 声明指向无绑定项目 | `403 CONTEXT_NOT_ALLOWED`（不变；声明是错误的，不该静默进桶）|
| 策略凭证被停用/删除 | `502 route_unavailable`（沿用路由失败语义），usage 不记（未达上游）|
| 单绑定 Key | 不变（SOLE_BINDING）|

### 2.8 测试矩阵

- control-plane IT：PUT/GET/DELETE 全链（懒建桶、校验矩阵、审计、重复 PUT 幂等覆盖）；建 Key 选 system 项目 → 400；桶项目不进 `GET /admin/projects` 默认列表？——**决定：列表返回但带 `system` 字段，前端过滤**（Q3）。
- gateway 单测：阶梯终态矩阵（有/无策略 × claim/后缀/唯一/多绑定）；策略模型门控（空 scope=全目录；非空=交集；目录外模型拒绝）。
- gateway IT：多绑定 Key + 无上下文 + 策略 → 200 且 usage `resolution_status=POLICY_ROUTED`、project=桶；未配置策略 → 400（回归）。
- 前端 spec：设置页卡片（保存参数、清除、未配置文案）。

### 2.9 开放问题（提请评审）

- **Q1**：策略凭证被项目 grant 引用时——硬性禁止（严格"物理分离"）vs 告警放行？（方案默认：告警放行；理由：硬禁止难以在后续加 grant 时持续保证，且小租户常有复用诉求；但安全审查若要求，可加"被引用即禁止保存 + 后续加 grant 引用该凭证时 409"）
- **Q2**：策略路径的模型门控是否与 Key 自身 `virtual_key_models` 求交？（方案默认：**求交**——Key 是身份，其模型范围是用户可见边界；策略只补"项目/凭证"维度。与 Spec 只提"策略模型范围"的差异需评审确认）
- **Q3**：桶项目在管理端项目列表的可见性（方案默认：可见但带 `system` 标记、禁选）。
- **Q4**：`AMBIGUOUS`（多组 HIGH 冲突）是否也进桶？Spec §4 表倾向"是（按策略）"；方案默认遵循 Spec（AMBIGUOUS → 策略/400），但需确认"证据打架"与"完全无线索"共用同一兜底是否可接受（审计上以 `claim_status`/`resolution_status` 区分）。

## 3. ③ Agent 安装与自启（miqro-context）

### 3.1 命令面

```
miqro-context install [--write-config] [--autostart] [--gateway <url>]
miqro-context uninstall [--autostart]
miqro-context doctor        # 增：自启状态检查
```

### 3.2 三平台自启（仅 `--autostart` 时写入，全部用户级、免管理员）

| 平台 | 机制 | 卸载 |
|---|---|---|
| Windows | `%APPDATA%\...\Startup\miqro-context.cmd`（登录启动，免注册表权限）| 删除该文件 |
| macOS | `~/Library/LaunchAgents/com.miqro.context.plist` + `launchctl load` | `launchctl unload` + 删文件 |
| Linux | `~/.config/systemd/user/miqro-context.service` + `systemctl --user enable --now` | disable + 删文件 |

- 生成器纯函数（内容可单测）；写入路径支持 `MIQRO_CONTEXT_AUTOSTART_DIR` 覆盖（测试沙箱，不在测试里碰真实自启目录）。
- 幂等：重复 install 覆盖同路径文件；`uninstall` 不存在时静默成功。
- 日志：自启运行输出到 `~/.miqro/agent.log`（轮转：单文件 5MB 截断）。

### 3.3 测试

- 单测：三平台文件内容快照、幂等、uninstall；`install --write-config` 写 0600 配置。
- 本机手工：Windows Startup 文件生成 + `--autostart none` 沙箱路径验证；真实自启**不在开发机自动注册**（用户验收时手动执行）。

## 4. ④ 观察项：`/v1/models` identity-only

- 现状：`/v1/models` 走 `resolve()` → 多绑定 Key 不匹配后缀 400。评估：模型列表与"归属哪个项目"无关（四路交集不含 binding 维度？**实施前核对** `allowedModels(ctx)` 是否用到 binding.grantId——若用到，需先定义多绑定下的 /v1/models 语义，再决定 identity-only 化）。
- 决策倾向：核对后若与 binding 无关 → 改 identity-only（同 #641 先例，独立小 PR）。

## 5. 安全与审计要点（供审查）

- 策略是**租户级显式配置**（默认关），不改变"未配置即拒绝"的失败关闭基线；
- 桶项目 `system=true`，不可被建 Key 选择、不可作为 grant 目标（校验同建 Key）；
- 所有入口（PUT/DELETE/建 Key 拒绝）写审计；`usage_event` 的 `resolution_status=POLICY_ROUTED` + 原样保留 claimed_* 使每笔桶内消费可解释；
- 声明仍是不可信输入（#633 不变量不变）；策略不让任何声明获得额外权限。

## 6. 交付顺序与留痕

| 序 | 内容 | issue | PR 约定 |
|---|---|---|---|
| 1 | 本方案 MD | tracking issue | docs PR（本文） |
| 2 | ① 默认全选 | issue A | 前端 + spec + 验收清单增补 |
| 3 | ② 未归属策略 | issue B | V57+快照+网关+控制面+前端+IT（一个 PR，附测试证据） |
| 4 | ③ 安装/自启 | #648 | **已实现**（`install --autostart` / `uninstall --autostart` / doctor 状态；三平台生成器单测 + Windows 沙箱实测） |
| 5 | ④ /v1/models 核对 | issue D（视核对结果）| 独立小 PR 或关闭为"维持现状" |

- 每个 PR：issue 模板逐节填写；CI 全绿合并；progress.md 记录；演示站部署并复验；`usage_event`/审计可复算。
