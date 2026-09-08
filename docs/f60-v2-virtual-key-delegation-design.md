# F60 批 2 v2：Virtual Key 委托创建（设计稿，待 owner 圈定）

> 状态：DESIGN-READY（2026-09-08）。拍板后转 ADR 增补并实现。关联：ADR-0015/0016
> （机器凭据/写面 A+C，v1 已交付 #251）、VirtualKey 模型与 create 校验链
> （VirtualKeyService.create，vkey 1:1 绑定 user/project/grant/credential/purpose）。

## 问题

机器密钥（= 发行管理员的委托身份）目前能管告警/Webhook/导出，但**不能代用户建
Virtual Key**——这是「可编程管理 API」里管理员最常想要的自动化场景（开通流程、
批量发钥、CI 为指定员工建通道）。卡点：vkey 创建逻辑以「当前登录用户」为归属与
成员校验主体，机器没有 userId。

## 现状语义（create 校验链）

1. project：租户内、ACTIVE、**必须有路由 tag**；
2. 成员：非 SYSTEM_ADMIN 用户必须为该 project 成员；
3. grant：`credentialGrantId` 必须与 (project, providerProduct) 匹配且 ACTIVE；
4. models ⊆ grant 模型集；cachePolicy opt-in；key.userId = 创建者。

## 方案

### 案 1（推荐）：委托=管理员代操作，保留目标用户的项目成员边界
新增 `createForUser(operatorIssuerId, targetUser, request)`，校验链：
- operator = 机器委托人（SYSTEM_ADMIN 会话或密钥的发行管理员）；
- **targetUser**：租户内、ACTIVE；
- project/grant/models/cachePolicy 校验与现状一致；
- 成员校验按**目标用户**执行（target 为 SYSTEM_ADMIN 时豁免，与现状管理员自建一致）；
- `key.userId = target.id`；审计 actor = operator，action `VIRTUAL_KEY_CREATE`，
  summary 增加 `targetUserId`（沿审计链可回到人 + 钥归属双元）。
端点：`POST /api/v1/admin-api/virtual-keys`（body 复用 CreateVirtualKeyRequest +
`userId`）→ 201 `{key, secret, shownOnce:true}`；只读配套
`GET /api/v1/admin-api/virtual-keys?userId=`（视图列表）便于流程查询。

优点：爆炸半径受「目标成员关系」约束；语义清晰（管理员先加项目成员再代建，
等同现有 UI 心智）；复用现校验，改动小。
代价：自动化开号需先保证成员关系（一次额外调用/前置流程）。

### 案 2：管理员全权（绕过成员校验）
与案 1 相同但**跳过成员校验**（SYSTEM_ADMIN 语义平移到委托者）：管理员可在任意
ACTIVE+tag 项目内为任意 ACTIVE 用户建钥。
优点：流程最短。代价：越过 project 成员语义（授权边界弱化），建议至少要求
operator 角色为 SYSTEM_ADMIN（会话或密钥发行者本身须为 SYSTEM_ADMIN）。

### 案 3：v1 保持现状，v2 只加"给指定用户建"但限制 target 也是 SYSTEM_ADMIN
适用面极窄，不建议。

## 拍板点

1. 案 1 还是案 2（默认建议案 1）；
2. 端点命名 `virtual-keys` 与列表查询 userId 过滤是否够用；
3. 是否需要按目的（purpose）白名单限制机器可建类型（默认不限，与管理员一致）。

## 验收（拍板后）

- 集成：管理员发钥→机器代 target 建钥 201 → 钥归属 target、secret 一次性、吊销后网关 401
  → target 非成员时案 1 拒绝/案 2 放行 → 非管理员会话 403 → 审计 actor=委托人+targetUserId。
- 文档：ADR 增补 + api-contract §9 写面 + 示例集补建钥段。
