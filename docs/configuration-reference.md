# 配置参考

所有应用配置使用 `MIQROKEY_` 前缀。生产环境优先通过只读 Secret 文件注入敏感值；环境变量适合非敏感配置和 Secret 文件路径。不得把真实凭证、master key 或 Webhook Secret 写入 Compose、Git、镜像层或命令行参数。

## 1. 配置优先级

从高到低：启动参数（仅开发）、环境变量、外部 `application.yaml`、镜像默认值。生产禁止通过门户修改进程级安全配置。

布尔值只接受 `true/false`，时长使用 ISO-8601（如 `PT30S`），容量使用明确单位。未知 `MIQROKEY_` 配置在生产 profile 下应使启动失败，防止拼写错误静默失效。

## 2. 基础配置

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_ENVIRONMENT` | `development` | `development/test/production` |
| `MIQROKEY_PUBLIC_BASE_URL` | 无 | 门户公开 URL，生产必填 |
| `MIQROKEY_GATEWAY_BASE_URL` | 无 | 展示给用户的 Gateway Base URL，生产必填 |
| `MIQROKEY_TIME_ZONE` | `UTC` | 后台调度时区；存储仍为 UTC |
| `MIQROKEY_INSTANCE_ID` | 自动 | 审计和任务锁实例标识 |
| `MIQROKEY_CATALOG_PATH` | `/etc/miqrokey/catalog` | 只读供应商目录目录 |
| `MIQROKEY_DATA_PATH` | `/var/lib/miqrokey` | 导出、任务和本地运行数据 |
| `MIQROKEY_TEMP_PATH` | 系统临时目录 | 临时文件，必须同磁盘容量监控 |

## 3. 数据库

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_DB_URL` | `jdbc:postgresql://localhost:5432/miqrokey` | PostgreSQL JDBC URL |
| `MIQROKEY_DB_USERNAME` | `miqrokey` | 数据库账号 |
| `MIQROKEY_DB_PASSWORD_FILE` | 无 | 密码文件，生产必填 |
| `MIQROKEY_DB_POOL_MAX_SIZE` | `20` | Control Plane/usage 写入共享上限按部署校准 |
| `MIQROKEY_DB_CONNECT_TIMEOUT` | `PT5S` | 建连超时 |
| `MIQROKEY_DB_STATEMENT_TIMEOUT` | `PT30S` | 管理查询默认超时 |
| `MIQROKEY_DB_FLYWAY_ENABLED` | `true` | 生产允许迁移，但发布前必须演练 |

Gateway 不在事件循环中执行 JDBC；usage 写入进入有界队列和专用执行器。

## 4. 密钥与会话

### 4.1 Crypto 密钥配置

生产环境必须通过文件注入加密密钥；禁止将密钥写入环境变量、命令行参数或 Spring 属性。

```yaml
miqrokey.crypto.enabled: true
miqrokey.crypto.encryption.active-version: v1
miqrokey.crypto.encryption.versions[v1]: /etc/miqrokey/keys/master-key-v1.key
miqrokey.crypto.encryption.versions[v2]: /etc/miqrokey/keys/master-key-v2.key
miqrokey.crypto.hmac.active-version: v1
miqrokey.crypto.hmac.versions[v1]: /etc/miqrokey/keys/vk-hmac-v1.key
miqrokey.crypto.hmac.versions[v2]: /etc/miqrokey/keys/vk-hmac-v2.key
```

| Spring 属性 | 说明 |
|---|---:|
| `miqrokey.crypto.enabled` | 启用 crypto 自动配置；生产必为 `true` |
| `miqrokey.crypto.encryption.active-version` | 新加密使用的活跃密钥版本 ID |
| `miqrokey.crypto.encryption.versions[v1]` | 版本 → 密钥文件绝对路径；支持多版本用于轮换 |
| `miqrokey.crypto.hmac.active-version` | 新 Virtual Key 摘要使用的活跃 HMAC 版本 ID |
| `miqrokey.crypto.hmac.versions[v1]` | 版本 → HMAC 密钥文件绝对路径；支持多版本验证 |

### 4.2 密钥文件要求

- 必须为普通文件（拒绝符号链接、管道、目录）。
- POSIX 环境下必须为 `0400`（仅 owner 可读）；Windows 下至少需要进程可读。
- AES 主密钥：恰好 32 字节原始二进制或 base64 编码文本。
- HMAC 密钥：至少 32 字节原始二进制或 base64 编码文本。
- 拒绝全零、全相同字节（示例/弱密钥）。
- 主密钥和 HMAC 密钥必须为不同文件，且字节内容不同。

### 4.3 多版本轮换

1. 添加 `encryption.versions[v2]=/path/to/new-key.key`，设置 `active-version=v2`。
2. 重启后新加密使用 v2；旧版本 v1 保留用于解密。
3. 后台通过 `reEncrypt()` 把旧密文重新加密到 v2。
4. 全部迁移完成后从配置移除 v1，重启。

### 4.4 会话

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_BOOTSTRAP_SECRET_FILE` | 无 | 仅首个管理员创建时使用，完成后移除 |
| `MIQROKEY_SESSION_COOKIE_NAME` | `MIQROKEY_SESSION` | Secure/HttpOnly/SameSite cookie |
| `MIQROKEY_CSRF_COOKIE_NAME` | `MIQROKEY_CSRF` | non-HttpOnly/SameSite cookie（JavaScript 可读） |
| `MIQROKEY_SESSION_IDLE_TIMEOUT` | `PT30M` | 空闲失效 |
| `MIQROKEY_SESSION_ABSOLUTE_TIMEOUT` | `PT12H` | 绝对失效 |
| `MIQROKEY_LOGIN_MAX_FAILURES` | `5` | 渐进锁定阈值 |
| `MIQROKEY_LOGIN_LOCK_BASE` | `PT1M` | 首次锁定时长 |
| `MIQROKEY_VK_ROTATION_GRACE` | `PT5M` | 默认旧 Key 宽限；管理员可立即失效 |
| `MIQROKEY_PRODUCTION` | `false` | 生产模式：启用严格 Origin 验证、强制 cookie Secure 标志、拒绝 localhost 来源 |
| `MIQROKEY_ORIGIN_ALLOWLIST` | `localhost:5173,localhost:8080` | 生产模式下至少需要一个非 localhost 条目 |
| `MIQROKEY_COOKIE_SECURE` | `false` | Cookie Secure flag；生产模式下自动启用（可手动覆盖，但强制保持 true） |

主密钥和 HMAC 密钥不能复用。生产启动时若文件权限过宽、长度错误或使用示例值，必须失败。

### 4.5 生产模式约束

当 Spring `production` profile 激活或 `miqrokey.production=true` 时，启动时自动执行以下验证：

1. **Cookie Secure**：自动启用 `cookieSecure=true`（若未显式设置）。
2. **Origin Allowlist**：必须包含至少一个非 localhost 条目（如 `https://your-domain.com`）。
3. **启动失败**：allowlist 为空或仅含默认 localhost 值时，启动直接失败。

生产模式下，所有缺少/无效/未允许的 Origin 返回 `403 ORIGIN_REJECTED`；Cookie 自动设置 `Secure` flag；开发模式的 localhost 隐式放行被禁用。

## 5. Gateway 网络与流式

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_GATEWAY_PORT` | `8081` | 数据面端口 |
| `MIQROKEY_CONTROL_PORT` | `8080` | 管理面端口 |
| `MIQROKEY_UPSTREAM_URL` | 空 | 仅 Phase 0 固定路由 PoC 使用；后续由 Virtual Key 路由快照提供 |
| `MIQROKEY_UPSTREAM_CONNECT_TIMEOUT` | `PT5S` | 上游连接超时 |
| `MIQROKEY_UPSTREAM_RESPONSE_TIMEOUT` | `PT10M` | 整体上限；流式空闲另算 |
| `MIQROKEY_UPSTREAM_STREAM_IDLE_TIMEOUT` | `PT2M` | SSE 无数据超时 |
| `MIQROKEY_MAX_INBOUND_HEADER_BYTES` | `32KB` | Header 上限 |
| `MIQROKEY_MAX_CONTROL_BODY_BYTES` | `1MB` | 管理 API body 上限 |
| `MIQROKEY_MAX_PROXY_BUFFER_BYTES` | `256KB` | 只限制必要解析缓冲，不聚合完整响应 |
| `MIQROKEY_MAX_CONCURRENT_STREAMS` | `50` | 首版容量目标；不是用户限流策略 |
| `MIQROKEY_TRUSTED_PROXY_CIDRS` | 空 | 仅从这些代理接受 forwarded headers |
| `MIQROKEY_UPSTREAM_ALLOWED_CIDRS` | 空 | 自定义端点的额外 allowlist |
| `MIQROKEY_UPSTREAM_FOLLOW_REDIRECTS` | `false` | 默认禁止 |

`MIQROKEY_MAX_CONCURRENT_STREAMS` 是保护实例稳定性的容量边界，不是按用户/团队配额。达到物理上限时返回明确的 `503 CAPACITY_EXHAUSTED` 并告警。

## 6. Usage、成本与后台任务

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_USAGE_QUEUE_CAPACITY` | `10000` | 有界内存队列 |
| `MIQROKEY_USAGE_WRITER_THREADS` | `4` | JDBC 专用写入线程 |
| `MIQROKEY_USAGE_BATCH_SIZE` | `100` | 批量写入上限 |
| `MIQROKEY_USAGE_FLUSH_INTERVAL` | `PT1S` | 刷新周期 |
| `MIQROKEY_USAGE_RETENTION_MODE` | `MANUAL_ONLY` | 首版永久保留直到人工删除 |
| `MIQROKEY_PLAN_SYNC_INTERVAL` | `PT15M` | 余额/周期同步 |
| `MIQROKEY_MODEL_SYNC_INTERVAL` | `PT6H` | 模型目录同步 |
| `MIQROKEY_PRICE_CATALOG_PATH` | `/etc/miqrokey/prices` | 版本化价格目录 |
| `MIQROKEY_EXPORT_MAX_RANGE` | `P366D` | 单次导出最大时间窗 |
| `MIQROKEY_EXPORT_LINK_TTL` | `PT1H` | 下载链接到期 |

队列达到高水位必须告警；队列满时不能静默丢弃。实现按照架构文档选择短暂背压/失败和补偿记录，行为必须有集成测试。

## 7. Webhook 与告警

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_WEBHOOK_ENABLED` | `true` | 全局开关 |
| `MIQROKEY_WEBHOOK_CONNECT_TIMEOUT` | `PT5S` | 连接超时 |
| `MIQROKEY_WEBHOOK_REQUEST_TIMEOUT` | `PT10S` | 请求超时 |
| `MIQROKEY_WEBHOOK_MAX_ATTEMPTS` | `6` | 指数退避次数 |
| `MIQROKEY_WEBHOOK_MAX_AGE` | `P1D` | 最长重试窗口 |
| `MIQROKEY_WEBHOOK_SIGNATURE_HEADER` | `X-MiQroKey-Signature-256` | HMAC-SHA256 签名 Header |

目标 URL 和 Secret 由管理员在数据库配置；Secret 加密保存。发送器必须实施 SSRF 校验，并禁止重定向逃逸。

## 8. 备份与可观测性

| 配置 | 默认 | 说明 |
|---|---:|---|
| `MIQROKEY_BACKUP_SCHEDULE` | `0 0 2 * * *` | 每日 02:00（按 `MIQROKEY_TIME_ZONE`） |
| `MIQROKEY_BACKUP_DAILY_KEEP` | `7` | 每日备份数量 |
| `MIQROKEY_BACKUP_WEEKLY_KEEP` | `4` | 每周备份数量 |
| `MIQROKEY_BACKUP_PATH` | `/var/backups/miqrokey` | 应映射到独立存储 |
| `MIQROKEY_BACKUP_KEY_FILE` | 无 | 备份加密密钥，必须与在线 master key 分离 |
| `MIQROKEY_METRICS_ENABLED` | `true` | Prometheus 指标 |
| `MIQROKEY_METRICS_PATH` | `/actuator/prometheus` | 仅管理网络暴露 |
| `MIQROKEY_LOG_LEVEL` | `INFO` | 生产禁止默认 DEBUG |

指标标签不得使用用户 ID、完整模型输入、Key、request body 或供应商错误正文等高基数/敏感值。

## 9. Cache 扩展位

首版 `MIQROKEY_RESPONSE_CACHE_ENABLED` 固定为 `false`。代码可提供 `ResponseCache` SPI 和 `NoOpResponseCache`，但不得因为配置出现就实际缓存。未来启用前必须新增 ADR，解决授权域、模型参数、工具调用、流式重放、加密、删除和供应商 Prompt Cache 语义。

Gateway 必须透明保留供应商自己的 Prompt Cache Header/字段，并单独统计 cache token；这与本系统响应缓存无关。

## 10. 生产启动校验

生产 profile 在以下情况拒绝启动：缺少公开 URL、DB password/master/HMAC key 文件；默认/弱密钥；Cookie 非 Secure；数据库不是受支持版本；目录签名失败；导出或备份目录不可写；上游 Base URL 使用不允许的 scheme；开启响应缓存；Flyway 校验失败。
