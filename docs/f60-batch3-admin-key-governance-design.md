# F60 批 3：管理密钥治理（scope 作用域 + 过期提醒）设计稿

> 状态：DESIGN-READY（2026-09-09 起草，待 owner 拍板后转 ADR-0015 增补并实现）。
> 关联：ADR-0015（机器凭据，批 1/1b 已交付）、ADR-0016（写面 A+C，批 2 v1/v2 已交付）、
> docs/open-admin-api-plan.md（批 3 原描述"作用域（只读/写）、过期提醒、频控——可选"）。

## 问题

当前每把管理密钥都是**租户级全量**：可读全部开放读面，可写 alert-rules/webhooks/
export-tasks/virtual-keys（批 2 v2 委托）。最小权限建议只能靠「密钥命名 + 信任」落实；
自动化场景（CI 只读用量、运维只管理告警、开通脚本只代建钥）拿到的钥匙权限都一样大，
一旦泄露或转手，爆炸半径 = 全租户管理面。

## 目标与边界

- 目标：给机器密钥**可选的**权限裁剪，让"最小权限建议"变成**可强制**的机制；过期提醒让
  例行轮换可被发现。
- 非目标：不做频控（沿用 F05 思路"默认不限制"，可后续单独立项）；不改人类会话面；
  不引入 SDK/策略引擎。**默认兼容**：不给新密钥配 scope = 现状全量，绝不因本批收紧存量。

## 方案

### 作用域模型（案 A：预设能力组，推荐）
scope 为一组预设能力位（存储为 JSON 数组列，空=全量）：

| 能力组 code | 覆盖端点 | 典型用途 |
|---|---|---|
| `usage:read` | usage summary/records、audit-events、api-keys 视图、quota-rules 读、mcp-access-logs | CI 巡检/报表 |
| `alerts:write` | alert-rules、webhooks 全生命周期 | 告警自动化 |
| `exports:create` | export-tasks 创建与元数据（A 委托语义不变） | 数据管道 |
| `vkeys:delegate` | virtual-keys 委托创建/列表（案 1 边界语义不变） | 开号自动化 |

- 优点：可枚举、好审计、好写文档；覆盖已交付目录的自然分组；扩展=加一组。
- 代价：粒度是"组"不是任意端点白名单——内部 50 人规模足够。
- 拒绝项：**端点级任意白名单**（案 B，配置面无限、难审查）、**只读/写二态**（太粗，
  alerts 只写与 usage 只读放一个"写"位互相污染）。

### 实施要点
1. V35：`admin_api_key.scope jsonb NULL`（NULL=全量，兼容存量）+ 迁移零默认。
2. 端点：`PATCH /api/v1/admin/api-keys/{id}/scope`（SYSTEM_ADMIN-only，body
   `{capabilities: [...]}`，校验枚举+去重；清空=全量）；列表/详情返回 capabilities。
3. 强制层：AdminApiKeyAuthFilter 解析 key.scope → request attr `ADMIN_API_SCOPE`；
   每个开放面控制器按路径声明所需能力组（集中一张映射表或注解），filter/拦截器
   统一校验：能力不足 → 403 `ADMIN_API_SCOPE_DENIED`（problem+json，进审计）。
4. 审计：`ADMIN_API_KEY_SCOPE_UPDATE`（actor=管理员，摘要含旧/新 capabilities）；
   越权尝试本身也记审计（`ADMIN_API_SCOPE_DENIED` 事件，防探测）。
5. 文档/示例：open-admin-api-plan 批 3 段 + examples 加"最小权限组合"建议表。
6. 测试矩阵：默认全量回归；各能力组只通本组端点、他组 403；NULL 兼容；跨租户隔离不变。

### 过期提醒（独立小件，可同批或拆批）
- 现状：`expires_at` 可选、吊销即时，但到期前无提醒（密钥静默失效=自动化中断事故）。
- 方案：管理密钥列表页加"即将到期"徽标（≤7 天）；可选接入告警框架新增事件类型
  `ADMIN_API_KEY_EXPIRING`（每日评估一次，webhook 投递，复用 alert infra）。
- 拍板点：只要 UI 徽标，还是同时要 webhook 事件（推荐都要，事件默认关）。

## 拍板点
1. 案 A 预设能力组 vs 案 B 任意端点白名单（默认 A）；
2. 初始能力组集合是否够用（上表 4 组）还是需要拆分/增加；
3. 过期提醒范围：UI 徽标 + webhook 事件都做，还是先 UI；
4. scope 变更与吊销、重建的组合语义（默认：吊销重建携带相同 scope 由调用方显式传入，
   不隐式继承）。

## 验收（拍板后）
- 集成：配 scope 密钥→本组端点 200/他组 403（含读面与写面样例）；NULL 密钥全量回归；
  PATCH 审计；越权 403 进审计；跨租户 401/404 不变；基线再生无 breaking。
- 文档：ADR-0015 增补 + api-contract §9 + 示例集最小权限表升级 + feature-backlog F60 行。
