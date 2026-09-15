# 部署与运维

## 1. 标准交付

首版使用 Linux Docker Compose：

```text
reverse-proxy
├── /api/*          → control-plane-app
├── /               → Vue 静态页面
└── inference paths → gateway-app

gateway-app ────────┐
control-plane-app ──┼── PostgreSQL
backup-job ─────────┘
```

不包含 Redis。Prometheus 和 Grafana 通过可选 Compose Profile 启动。

## 2. 配置

敏感配置通过 Docker Secret 或只读文件：

- 主加密密钥；
- Virtual Key HMAC pepper；
- 初始管理员引导 Secret；
- Webhook 签名 Secret；
- 数据库密码。

普通环境变量只保存非敏感配置。仓库提供 `.env.example`，不得提供真实 Secret。

## 3. HTTPS

生产由反向代理或客户现有入口处理 TLS。应用只信任明确配置的代理，并正确处理 `Forwarded`/`X-Forwarded-*`。禁止直接把未加密容器端口暴露到公网。

## 4. 健康检查

- Liveness：进程事件循环可响应，不检查外部供应商。
- Readiness：数据库、路由快照和加密 Provider 可用。
- Dependency status：供应商凭证状态单独展示，不因某一家供应商失败让整个 Gateway 不就绪。
- Graceful shutdown：停止接收新请求，等待在途流式请求至配置上限，再取消退出。

## 5. 监控

Micrometer/Prometheus 指标至少包括：

- 请求数、并发流、状态与协议；
- Gateway 延迟、首包延迟、上游延迟；
- Token 与 cache read/write Token；
- usage 解析失败；
- 数据库写入与缓冲队列；
- 凭证验证和 Plan 同步；
- Webhook 投递与备份状态。

指标标签禁止包含 username、完整 Key、request ID 或高基数任意 model 字符串。model 只使用目录中受控 ID，必要时聚合。

提供 Grafana Dashboard JSON，但默认 Compose 不启动完整监控栈。

## 6. 日志

- 结构化 JSON 输出到 stdout。
- 使用 request ID 关联 Gateway 和 Control Plane。
- 默认 INFO，不记录正文和凭证。
- 错误日志对供应商返回体做字段白名单和截断。
- 客户通过容器日志驱动或现有平台采集。

## 7. 备份

默认每天 Asia/Shanghai 02:00 执行 PostgreSQL 压缩备份：

- 保留 7 个每日备份；
- 保留 4 个每周备份；
- 生成 SHA-256 校验值；
- 超过 26 小时无成功备份或备份失败时发送 Webhook。

本机备份不能防止主机损坏。客户必须把数据库备份和主加密密钥分别复制到其他受保护位置。主加密密钥不能打包进数据库备份。

每次发布前在临时环境执行恢复演练，验证：账号、加密凭证、Virtual Key 摘要、流水和审计可用。

## 8. 升级

- 镜像使用明确语义版本和 digest。
- Flyway migration 向前执行，破坏性迁移必须拆成 expand/migrate/contract。
- 升级前自动检查备份新鲜度。
- Gateway 和 Control Plane 可分别滚动升级；Docker Compose 首版允许短暂控制面维护，但尽量不影响在途推理。
- 供应商签名目录可独立更新和回滚。

## 9. Windows 开发

开发者环境：

- Windows 11；
- JDK 21；
- Node.js LTS；
- Docker Desktop + WSL2；
- Maven Wrapper 与 npm/pnpm 锁文件。

测试分层：

1. Windows 原生运行 Java/Vue 单元测试。
2. Testcontainers 或 Docker Desktop 运行 PostgreSQL、Mock Provider 集成测试。
3. 完整 Docker Compose 冒烟测试。
4. CI 在 Linux 上构建最终镜像并运行部署验收。

所有脚本应提供 PowerShell 与 POSIX 兼容入口，或使用 Maven/Node 跨平台任务，避免只支持 Bash。

## 10. Kubernetes 后续迁移

首版不交付 Helm Chart，但保持：

- Gateway 和 Control Plane 无本地持久状态；
- Secret 外部注入；
- 健康检查标准化；
- 优雅停机；
- 配置与镜像分离；
- 数据库任务使用分布式锁。

未来迁移 Kubernetes 时，PostgreSQL 可以换成客户托管实例，Gateway 增加副本即可。

## 11. 容器镜像

`deploy/docker/` 四个镜像（一镜像一 Dockerfile），基础镜像全部按 digest 固定，应用运行层非 root：

| 镜像 | Dockerfile | 内容 |
|---|---|---|
| miqrokey-gateway / miqrokey-control-plane | `gateway.Dockerfile` / `control-plane.Dockerfile` | 镜像自带 Maven 3.9.9（与 wrapper 锁定同版本）+ Temurin 21 构建 fat jar → Temurin 21-jre-alpine 运行，`USER 10001` |
| miqrokey-portal | `portal.Dockerfile` | node:22-alpine 构建 Vue SPA → nginx 1.27-alpine 固化静态与 TLS 反代配置 |
| miqrokey-backup | `backup.Dockerfile` | postgres 17.6-alpine（pg_dump 与库同版本）+ bash/openssl/curl + `deploy/backup` 脚本，`USER postgres` |

在仓库根目录构建（`compose.prod.yaml` 会按需重建；手动构建示例）：

```bash
docker build -f deploy/docker/gateway.Dockerfile -t miqrokey-gateway .
docker build -f deploy/docker/control-plane.Dockerfile -t miqrokey-control-plane .
docker build -f deploy/docker/portal.Dockerfile -t miqrokey-portal .
docker build -f deploy/docker/backup.Dockerfile -t miqrokey-backup .
```

构建不声明 `# syntax` 前端镜像（受限网络拉不到 docker/dockerfile）；应用镜像不经容器内 mvnw 自举
（并行构建共享 m2 缓存时 wrapper 安装发行包存在竞态，实测失败）。

## 12. 生产编排（Docker Compose）

`deploy/compose.prod.yaml`：`portal`（nginx：TLS 终结 + SPA 静态 + `/api` → control-plane + `/v1`、`/mcpservers` → gateway；
唯一发布 80/443 的服务）+ `control-plane` + `gateway` + `postgres` + `backup`，内部网络 172.28.0.0/24。

```bash
cd deploy
cp .env.prod.example .env && chmod 600 .env       # 域名与 Origin 必填；密钥生成见 secrets/README.md
docker compose -f compose.prod.yaml up -d --build
```

- **密钥注入**：`secrets-init` 一次性容器把 `deploy/secrets/` 同步进命名卷 `secrets-store` 并在 Linux 侧设定属主/权限
  （master/vk_hmac/bootstrap `0400` 属主 10001；db_password/backup_key `0440` 属主 10001:70——postgres 镜像 uid 70
  可读）。不用 compose `secrets:`：Docker Desktop（Windows）对宿主文件呈现合成权限且忽略 `uid/gid/mode`（实测
  0777），无法得到确定的 0400。`db_password` 另由 postgres 镜像的 `POSTGRES_PASSWORD_FILE` 约定消费（指向卷内文件）。
- **`*_FILE` 约定**：`MIQROKEY_DB_PASSWORD_FILE` / `MIQROKEY_GATEWAY_DB_PASSWORD_FILE` 由两应用各自的
  `SecretFileEnvironmentPostProcessor` 解析为明文变量；备份容器 entrypoint 自行解析同名前缀。
- **Origin**：`MIQROKEY_ORIGIN_ALLOWLIST` 必须是无路径的 https 裸 origin（`https://your-domain`），
  否则 production 启动校验拒绝启动。
- **证书**：`deploy/secrets/certs/{fullchain,privkey}.pem`（本机冒烟可用自签）。
- **bootstrap**：首个管理员创建后删除 `MIQROKEY_BOOTSTRAP_SECRET_FILE` 行与密钥文件；公网部署建议
  `MIQROKEY_REGISTRATION_ENABLED=false`（示例默认）。
- **管理门户 IP 白名单**（可选）：`MIQROKEY_CONTROL_ADMIN_IP_ALLOWLIST` 与
  `MIQROKEY_CONTROL_ADMIN_TRUSTED_PROXIES=172.28.0.0/24`（XFF 仅从反代采纳）。
- **启动顺序**：postgres healthy → control-plane（Flyway 迁移）/gateway → portal；`docker compose ps` 全 `healthy` 后验收。
- **留痕 Kafka（可选 profile，#534）**：`docker compose -f compose.prod.yaml --profile kafka up -d` 额外拉起单节点
  Redpanda（Kafka 协议兼容；512M 内存约束、镜像 digest 固定；官方 `docker.redpanda.com` 大陆不可达——经 Docker
  Hub 镜像名拉取，2026-09-15 实测）。随后在 `.env` 设 `MIQROKEY_RETENTION_KAFKA_BOOTSTRAP_SERVERS=redpanda:9092`
  并 `up -d gateway`；租户留痕开关（默认关）打开后密文信封投递至 `content-retention` 主题（信封与消费协议见
  `retention-consumer.md`，开关/上限见 configuration-reference §R3）。**腾讯云 CKafka 为等价替代**：不启用本
  profile，直接把该变量设为 CKafka 接入地址。不带 profile 时服务列表与现状逐服务一致（零行为变化）。

## 13. 验证与备份容器

- `docker compose -f deploy/compose.prod.yaml config` 校验；CI 亦覆盖（含第三方镜像 digest 断言、四镜像构建与非 root 断言）。
- 反代验收（本机冒烟示例）：`curl -k --resolve miqrokey.local:443:127.0.0.1 https://miqrokey.local/healthz`（反代 200）、
  `/api/v1/auth/csrf`（控制面路由）、`/v1/models`（网关 401 鉴权语义）、`/`（门户 SPA）。
- `backup` 容器按日戳调度（每日 02:00，TZ=Asia/Shanghai，重启安全）执行 `miqrokey-backup.sh`
  （pg_dump → gzip → AES-256-CBC，密钥 `backup_key`），产物落命名卷 `backups`；结果经 `BACKUP_WEBHOOK_URL` 通知。
- 备份产物需另行同步异地（COS）；恢复演练见 operations-runbook。


