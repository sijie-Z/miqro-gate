# 门户 UI 规格

首版使用 Vue 3、TypeScript、Vite、Element Plus。门户面向约 50 个内部账号，优先保证准确、可审计和易运维。视觉语言、tokens、布局密度和禁用风格以 [`frontend-design.md`](frontend-design.md) 为准。

## 1. 路由和权限

```text
/login
/app
  /keys
  /keys/new
  /usage
  /profile
/admin
  /overview
  /users
  /teams
  /projects
  /providers
  /subscriptions
  /credentials
  /grants
  /virtual-keys
  /usage
  /reconciliation
  /exports
  /alerts
  /audit
  /settings
```

前端路由守卫只用于体验；所有权限必须由后端再次校验。普通用户导航不得出现管理员入口，也不能通过直接 URL 得到他人数据。

## 2. 通用交互

- 所有列表提供加载、空、错误、无权限状态；错误展示 `requestId` 便于排查。
- 危险动作使用动作名确认，不只提供“确定/取消”。吊销、删除用量等显示影响范围。
- 时间默认按浏览器时区展示，同时允许查看 UTC；导出 manifest 固定记录时区。
- 金额同时显示币种和数据来源；Plan 余额显示最后同步时间和权威级别。
- Key 只展示前缀及末四位；不提供“再次显示”按钮。
- 页面不得把 Secret 放入 URL、localStorage、埋点、错误上报或 DOM data attribute。
- 表单离开时若有未保存内容，提示确认。

## 3. 普通用户页面

### Virtual Keys

列表字段：名称、项目、供应商产品、用途、允许模型、掩码、状态、创建/最近使用时间。动作：创建、复制 Base URL、轮换、吊销。

创建向导只展示管理员已授权组合：

1. 选择项目。
2. 选择明确命名的供应商产品/Plan。
3. 选择用途和允许模型。
4. 确认固定绑定并创建。

创建成功弹窗只显示一次 Base URL 和 Virtual Key，分别提供复制按钮，并要求用户勾选“我已保存”。关闭后不可恢复明文。系统不提供 CC Switch 一键导入。

### Usage

展示当前用户按日期、Key、项目、产品、模型聚合的请求数、输入/输出/cache token、供应商用量、估算成本。允许查看有限时间窗的个人明细；不得出现同项目其他用户。

### Profile

展示账号信息、修改密码、当前会话和退出其他会话。首版无 MFA。

## 4. 管理员页面

### Overview

展示产品健康、未同步余额、低额度告警、usage 解析失败、Webhook 失败、近期请求量和成本。该页面不是强实时计费系统，所有卡片显示更新时间。

### Users / Teams / Projects / Grants

支持账号创建/禁用、组织关系和授权。Grant 编辑器必须能明确选择项目、产品、凭证/订阅、模型集合；保存前展示会影响多少用户和现有 Key。

### Providers / Subscriptions / Credentials

- Providers：供应商、具体产品、上游协议、Base URL、目录版本和 `DOCUMENTED/IMPLEMENTED/VERIFIED/DEGRADED`。
- Subscriptions：PAYG/个人/团队/企业；团队页按共享池、席位或成员 Subscription Key 展示，不强行统一。
- Credentials：创建时输入 Key；保存后只显示指纹、掩码、版本、验证和最近使用。轮换支持测试新版本、激活和回退窗口。
- 余额/周期剩余默认由官方能力拉取；不能拉取时显示“不可用”或“本地估算”，不要求管理员伪造剩余额度。

### Usage / Reconciliation / Exports

可按时间、用户、团队、项目、产品、凭证版本、Key、模型、供应商 request ID 查询。对账页并排展示本地记录与官方账单匹配状态；原始导出显示范围、格式、大小、校验和、状态和到期时间。

### Alerts / Audit / Settings

Webhook 配置包含 URL、事件、签名 Secret、测试、最近投递和重试。审计页只读。设置页包含目录、保留、会话和系统信息，但不能在线显示 master key。

## 5. 可访问性与响应式

- 键盘可完成登录、创建 Key、复制和确认；焦点状态清晰。
- 表单有可见 label，错误与字段关联，颜色不是唯一状态信号。
- 桌面 1280px 为主；小屏允许表格变卡片或水平滚动，但不能隐藏关键绑定信息。
- Secret 一次性弹窗打开时将焦点置于标题，关闭后回到触发按钮。

## 6. UI 验收

- 使用 Mock API 完成普通用户和管理员全部关键路径。
- E2E 证明普通用户看不到他人资源，且直接请求也返回 404。
- 刷新、重复提交和网络重试不会创建多个 Key/凭证版本。
- Secret 关闭后无法从页面状态、浏览器存储和历史请求恢复。
- 409/412/429/5xx、会话过期和 Webhook 测试失败均有可操作提示。
- 前端构建无 TypeScript 错误，关键视图通过基础无障碍检查。
