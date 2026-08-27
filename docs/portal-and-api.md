# 门户与管理 API

## 1. 门户语言与设计

首版中文优先，保留供应商、模型、Token、Base URL、Virtual Key 等通用英文术语。页面必须优先展示用户需要执行的动作，不把协议内部字段直接暴露给普通用户。

## 2. 普通用户页面

### 2.1 首页

- 自己本周期请求数、Token 和估算成本；
- 最近使用的 Virtual Key；
- 自己可用的供应商产品和模型；
- 自己请求的错误趋势；
- Key 长期未使用提醒。

不展示项目总量、Plan 总余额或其他用户。

### 2.2 Virtual Key 列表

- 名称、项目、供应商产品、用途；
- 前缀、末四位、状态；
- 模型数量、创建和最后使用时间；
- 创建、轮换、吊销操作。

### 2.3 创建 Virtual Key

级联选择项目、供应商产品、用途和模型。创建成功页只显示一次完整 Key 和 Base URL，并提供分别复制按钮。用户确认已保存后离开页面。

### 2.4 个人用量

按时间、Key、项目、供应商、产品和模型筛选，仅返回当前用户记录。

## 3. 管理员页面

### 3.1 账号与组织

- 用户创建、禁用、解锁和临时密码重置；
- 团队和项目；
- 项目成员；
- 项目供应商/凭证/模型授权。

### 3.2 供应商目录

- Provider 和 ProviderProduct；
- 文档/实现/验证状态；
- 协议、Base URL、模型和余额能力；
- 内置目录版本、签名和更新时间。

### 3.3 真实凭证与 Plan

- 选择供应商产品，只输入实际要求的 API Key；
- 连通性验证；
- 个人、团队、企业 Plan 信息；
- 多 Key、席位、成员 Key、共享池；
- 余额/周期来源和最后同步时间；
- 无感轮换、禁用和验证历史。

真实 Key 始终脱敏，不能再次完整查看。

### 3.4 统计与流水

- 全局、项目、用户、Key、真实凭证、Plan、模型维度；
- Token、缓存命中、错误、延迟、成本和内部摊销；
- CSV/JSONL 异步导出；
- 按时间范围手动删除。

### 3.5 告警与 Webhook

- Webhook URL、签名 Secret、启停和测试；
- 告警规则、阈值、去重窗口；
- 投递历史和手动重试。

## 4. API 风格

- 管理 API 使用 `/api/admin/**`。
- 普通用户 API 使用 `/api/me/**`。
- 会话 API 使用 `/api/auth/**`。
- 推理数据面保留供应商协议路径，不放在 `/api` 下。
- 使用 OpenAPI 生成前后端类型，但推理透明代理不强行生成完整供应商 schema。

## 5. 建议端点

```text
POST   /api/auth/login
POST   /api/auth/logout
POST   /api/auth/change-password

GET    /api/me/projects
GET    /api/me/provider-grants
GET    /api/me/virtual-keys
POST   /api/me/virtual-keys
POST   /api/me/virtual-keys/{id}/rotate
DELETE /api/me/virtual-keys/{id}
GET    /api/me/usage

GET    /api/admin/users
POST   /api/admin/users
PATCH  /api/admin/users/{id}
GET    /api/admin/projects
POST   /api/admin/projects
POST   /api/admin/projects/{id}/members
POST   /api/admin/projects/{id}/provider-grants

GET    /api/admin/provider-products
POST   /api/admin/subscriptions
POST   /api/admin/upstream-credentials/validate
POST   /api/admin/upstream-credentials/{id}/rotate
GET    /api/admin/quota-snapshots

GET    /api/admin/usage
POST   /api/admin/exports
GET    /api/admin/exports/{id}
POST   /api/admin/usage-deletions/preview
POST   /api/admin/usage-deletions

GET    /api/admin/alert-rules
POST   /api/admin/alert-rules
POST   /api/admin/webhooks/test
GET    /api/admin/audit-events
```

## 6. 错误格式

管理 API 使用统一 Problem Details；推理 API 尽量保持调用协议期望的错误格式。Gateway 自身拒绝请求时，按入站协议解析器生成兼容错误，并附加内部 request ID，不泄漏真实上游信息。

## 7. 异步任务

导出、删除、成本分摊、目录导入和批量凭证状态刷新使用数据库任务表。首版可以由 Control Plane 内置调度器执行，使用 PostgreSQL advisory lock 防止重复执行，不引入独立消息队列。

