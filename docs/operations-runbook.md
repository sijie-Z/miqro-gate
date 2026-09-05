# 运维 Runbook

适用于单客户 Docker Compose 私有化部署。操作前确认目标环境、备份状态和审计身份；命令以实际 Compose 文件和 Secret 路径为准，不在本文写入真实值。

## 1. 日常检查

- Control Plane 和 Gateway readiness/health 正常。
- PostgreSQL 可连接，Flyway 版本一致，无长事务和容量告警。
- usage 队列深度、解析失败率、请求错误率、SSE 活跃数在正常范围。
- Provider credential/Plan 最近同步时间，余额/周期告警，无 `DEGRADED` 产品。
- Webhook 投递积压、导出任务和备份最近成功时间。
- 磁盘空间覆盖 PostgreSQL、导出、临时文件和备份增长。

## 2. 启动与停止

启动前验证 Secret 文件权限、备份目录、配置和目录签名，运行 `docker compose config`。先启动 PostgreSQL并等待健康，再启动 Control Plane/Gateway/Portal。发布后依次检查 migration、readiness、登录、`/v1/models` 和一个 Mock/低成本烟雾请求。

停止时先从入口摘除新流量，等待活动流在维护窗口内结束，再停止应用，最后停止数据库。除紧急事件外不要直接中断所有流。


### 发布后闭环冒烟（注册 → 加入 → Key → 真实推理 → 用量核对）

本地开发环境可运行 `miqro-local/demo-registration-loop.sh`（不入库）自动执行六步；私有化环境按同步骤人工核对（替换账号/项目名）：

1. 管理员登录 → 定位目标用户（无则创建）。
2. `GET /admin/users/{id}/project-memberships` 为空 → 管理员把用户加入 ACTIVE 项目 → 列表出现该项目（快捷加入闭环）。
3. 用户登录 → `me/grants` 含该项目授权与模型集。
4. 用授权模型集创建 Virtual Key（`allowedModels` 必须取自 grant 模型，否则网关 403 `model_not_allowed`）。
5. 经网关发一次最小推理（建议 max_tokens≤8）：期望 200 且返回 model 字段；400 多为请求体编码/JSON 问题，403 多为模型未授权。
6. 数秒后 `me/usage/summary` 应有 upstream=1 与 tokens>0；usage_event 表新增行（`is_complete=true`）。若推理 200 但 usage 持续为空：查网关日志 `Usage batch write failed`（旧 jar 部署常见；以最新代码重建）。

## 3. 新增/轮换真实凭证

1. 管理员创建或选择供应商产品/订阅。
2. 输入新 Key，系统加密保存为非激活版本并执行官方最小验证。
3. 核对协议、模型和 Plan/团队形态；验证失败不得激活。
4. 激活新版本。新请求立即使用新版本，既有流保持旧版本到结束。
5. 观察错误率和 usage；必要时在旧版本尚可用时回退。
6. 在供应商侧撤销旧 Key，并在系统中关闭旧版本；全程写审计。

若供应商强制立即吊销，跳过宽限但通知受影响用户。不得通过群聊发送真实 Key。

## 4. 吊销 Virtual Key

确认 Key 掩码、所属用户/项目/产品和最近使用，执行立即吊销。新请求立刻拒绝；是否取消既有流按安全事件等级决定并记录。怀疑泄漏时同时撤销相关会话、检查 IP/模型/用量异常，并建议用户轮换 CC Switch 配置。

## 5. 供应商故障

系统不自动跨供应商切换：

1. 用官方状态页/RSS、网络检查和供应商 request ID 确认范围。
2. 把产品标记为 `DEGRADED`，触发 Webhook，向内部用户群发通知。
3. 保留上游状态/错误体语义，不伪装为其他模型。
4. 用户自行在 CC Switch 选择另一个已配置供应商/Key。
5. 恢复后执行烟雾测试，再解除状态并发布恢复消息。

## 6. 额度或周期告警

确认数据来源和最后同步时间。官方数据可用时与供应商控制台/账单核对；只有本地估算时明确告知误差。管理员负责充值、续期或增加团队席位。系统首版不因为软预算自动拒绝请求，但物理上游额度耗尽仍会产生上游错误。

## 7. Usage 异常

### 解析失败

用户响应不受影响。按 adapter/product/protocol 聚合失败，使用脱敏 fixture 复现；修复解析器后执行幂等补偿。未经官方数据支持不得把空值改成本地“官方 token”。

### 队列高水位或满

检查数据库延迟、锁、连接池和磁盘。先恢复写入能力，不无限扩大内存。队列满导致的请求失败/背压必须告警并统计；根据本地 request ID 和供应商明细补偿，不允许静默丢记录。

### 本地与官方账单不一致

按供应商 request ID 匹配，再检查时区、计费周期、模型别名、cache token、失败请求收费、价格版本和供应商延迟。导出本地原始 JSONL/CSV 与官方文件，保留 manifest 和 SHA-256。差异修正使用追加 adjustment，不覆盖原始事实。

## 8. Webhook 故障

检查 DNS/TLS、SSRF 拒绝原因、响应码和签名时钟偏差。系统按指数退避重试；超过窗口转 dead-letter 并在门户告警。测试投递使用独立事件，不重放真实 Secret 或用量明细。恢复后可人工重放选定事件，必须幂等。

## 9. 数据库故障

- Gateway 无法验证未缓存路由时 fail closed，不绕过 Virtual Key 鉴权。
- 已发布的不可变路由快照可在短暂数据库故障期间按架构定义继续服务，但新吊销传播存在明确上限并告警。
- 禁止手工修改业务表“修好状态”；使用受审查 SQL/迁移并备份。
- 恢复后检查 usage 队列、任务锁、会话、目录版本和审计连续性。

## 10. 备份与恢复

每日 02:00 备份，保留 7 个每日和 4 个每周副本；备份使用与在线 master key 分离的密钥，并存放于独立介质。备份包含 PostgreSQL、目录/价格版本、必要配置和加密凭证密文，不包含运行日志正文。

恢复演练至少每季度一次：在隔离环境恢复数据库和文件，加载正确 master/backup key，验证 Flyway、管理员登录、凭证可解密、Key 鉴权、usage 总数/校验和和导出。未完成恢复验证的备份不能算成功。

## 11. Master/HMAC Key

正常轮换采用 key version：新写入使用新 key，后台分批重加密，旧 key 保留到所有密文迁移和备份策略确认后再退出。HMAC key 轮换会影响 Virtual Key 验证，应支持多版本校验并逐步轮换用户 Key。

Master key 丢失无法从数据库恢复真实凭证；使用受保护备份恢复，或重新录入全部上游 Key。不得设计后门或把明文写日志。HMAC key 丢失时现有 Virtual Key 无法验证，必须批量轮换并通知用户。

## 12. 人工删除用量

原始记录默认永久保留。删除只由管理员发起：先生成受影响范围和行数预览，要求二次确认，创建不可变删除任务和审计事件，按分区/批次执行，最后保存删除范围、行数和校验信息。删除后无法从在线库恢复，只能从仍在保留期的备份恢复；操作前必须明确告知。

## 13. 事件取证

保留 request ID、时间、账号、Virtual Key ID、凭证版本、产品、模型、状态、token/费用、来源 IP（按客户策略）和审计事件。不得为了排错临时开启 prompt、代码或 response body 日志。需要协议样本时使用合成请求或经批准的完全脱敏 capture。


## 备份与恢复（G6.2）

脚本位于 `deploy/backup/`：

| 脚本 | 用途 |
|---|---|
| `miqrokey-backup.sh` | pg_dump(custom) → gzip → AES-256-CBC(PBKDF2 200k) → `<BACKUP_PATH>/miqrokey-<UTC 时间戳>.sql.gz.enc` + SHA-256 manifest；保留上限 `DAILY_KEEP + WEEKLY_KEEP`（默认 7+4），超出删最旧；成功/失败经 Webhook（可选 HMAC-SHA256 签名 `X-MiQroKey-Signature: sha256=...`）通知 |
| `miqrokey-verify.sh <file>` | 校验 manifest + 解密干跑（`pg_restore --list`），不触碰任何库 |
| `miqrokey-restore.sh <file> [target-db]` | 校验 manifest → 解密 → `pg_restore --exit-on-error`；恢复前目标库必须存在 |
| `test-restore.sh` | 真实恢复演练：双 Postgres 容器 → 播种 1000 行 → 真备份 → 校验 → 恢复 → 行数一致断言（已验证 PASS） |
| `test-retention-webhook.sh` | 保留上限与 Webhook 签名通知测试（已验证 PASS） |

### 每日备份（cron）

```bash
# 02:00 每日；密钥文件 32 字节 base64、权限 0400，与在线主密钥分离存储
0 2 * * * /opt/miqrokey/deploy/backup/miqrokey-backup.sh >> /var/log/miqrokey-backup.log 2>&1
```

环境变量：`MIQROKEY_BACKUP_PATH`、`MIQROKEY_BACKUP_DAILY_KEEP`、`MIQROKEY_BACKUP_WEEKLY_KEEP`、`MIQROKEY_BACKUP_KEY_FILE`（必需）、`MIQROKEY_BACKUP_WEBHOOK_URL`/`_SECRET`（可选）。退出码：0=成功，1=转储/加密失败，2=保留失败，3=备份成功但通知失败。

### 恢复演练要求

- 每季度至少一次 `test-restore.sh` 或对最新备份执行 `verify + restore` 到隔离实例。
- 备份加密密钥离线/分离保管；`restore` 与 `verify` 均强校验 SHA-256 manifest。

## 性能与浸泡（G6.4）

| 工具 | 用途 |
|---|---|
| `backend/gateway-app` `SoakIntegrationTest`（`@Tag("soak")`） | 真实 gateway + mock 上游 + PostgreSQL 的 30 秒并发流浸泡：断言 0 上游错误、全部请求落库（队列 drop 会表现为缺行）；CI 全量套件内运行 |
| `deploy/loadtest/soak.sh` | 生产类环境的长时间浸泡：对运行中的 stack 并发流式请求，报告吞吐/延迟分位/错误率 + usage 队列 drop 计数（须为 0） |

浸泡验收基线（首版）：并发 20 流持续 30 分钟无错误、usage 队列 drop 恒为 0、p99 延迟 ≤ 2× 基线。指标经 `monitoring` profile 的 `/actuator/prometheus` 观察。
