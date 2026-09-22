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
- **升级前必须人工确认备份新鲜度——系统不自动检查**：唯一的升级入口 `deploy/deploy.sh`（见 §8.1）和升级前门禁 `deploy/preflight/miqrokey-migration-preflight.sh` 都不读备份；口径是 §7 的「最近一次成功备份距今不超过 26 小时」，做法见 operations-runbook §9b.1 与 §9b.5。没有 undo 迁移，回滚只能靠升级前的备份。
- Gateway 和 Control Plane 可分别滚动升级；Docker Compose 首版允许短暂控制面维护，但尽量不影响在途推理。
- 供应商签名目录可独立更新和回滚。

### 8.1 单机升级的执行方式：`deploy/deploy.sh`

单机（Docker Compose）升级**只有一条入口**：`deploy/deploy.sh`。

```sh
deploy/deploy.sh --context /opt/miqrokey-dev --commit <sha> --services "control-plane portal" \
                 --caller <会话名> --smoke-url https://<origin>/ --smoke-origin https://<origin>
```

- `--context`：本次要部署的**构建树**（持有 `backend/` `frontend/` `deploy/` 的那一份），**不是**线上树
- `--dry-run`：只打印计划、不执行任何命令——**在陌生机器上先跑这个**
- `--verify-only`：不构建也不换容器，只做断言与冒烟。部署前问"我要动的是什么"、部署后问"还是不是当初那份"，都用它
- `--smoke-url` / `--smoke-origin` / `--smoke-expect`：见下"它能证明什么"

**它保证的四件事：**

1. **同一时刻只有一次部署**（`flock`），构建与 `up` 都在锁内。交错真正伤人的地方是**构建**：同一 tag 被并发构建两次之后，事后无法从机器状态回答"当时跑的是哪一份"。
2. **正在跑的就是刚构建的**：逐个比较 `docker inspect <容器>.Image` 与 `docker image inspect <tag>.Id`。`Up N seconds (healthy)` **不是证据**——容器没换过去时，机器显示的是一模一样的状态。
3. **配置确实加载了**：compose 的项目目录钉在 **compose 文件所在目录**（`$LIVE_DIR/deploy`），且 `.env` 以 `--env-file` **显式**指定、**缺失即失败**。compose 从*项目目录*读 `.env`；把它指到上一层（#794 这么干过）会让变量全部回落到 compose 里的默认值——容器起来是健康的、镜像是对的，**却在拒绝自己的 origin（403）**。
4. **证书确实进了容器**：宿主 `deploy/secrets/certs/{fullchain,privkey}.pem` 存在时，挂载它的容器里必须是**同样的字节**（比对 sha256），且挂载源必须解析到宿主那个目录。这是 #794 的**第二层**——相对挂载 `./secrets/certs` 锚错目录时 Docker 会在错误路径**现建一个空目录**，nginx 因证书缺失**崩溃重启**，而 `up` 零报错。上面三条都看不见它，且各有各的原因：镜像是**对的**、证书**不是环境变量**、而崩溃的 portal 让冒烟拿到 `000`（按设计只判 WARNING，因为"够不到"被当作天气）。**没有这条断言时，这个形状的故障是以"部署成功"收场的。**

**它能证明什么、不能证明什么**——这条比上面三条都值得记住：第 1、2 条是**发布动作**的正确性（换没换、换的是不是那份），**不涉及功能是否正确**。一次配置回归可以让一个容器"镜像 ID 完全正确、healthy、并且拒绝所有登录"。所以：

- `--smoke-url` 让本次运行去问运行中的栈要一个**真实客户端会要的东西**，状态码不匹配即失败（`--smoke-expect` 用 `|` 分隔可选项如 `'200|401'`；默认取决于目标是谁选的——脚本自己推的 `'2??|400|401'`，运维给的 URL 是 `2??`）
- `--smoke-method` / `--smoke-data`：方法与 JSON 请求体。**默认目标是按"这个请求能不能看见失败"选的**：`OriginInterceptor` 只守状态变更方法（`POST/PUT/PATCH/DELETE`），而且它是 `HandlerInterceptor`——在 handler mapping **之后**运行，所以 GET 既不进检查，打到只收 POST 的路由还会被 mapping 先回 405。**因此默认目标是 `POST <origin>/api/v1/auth/login` + 空 JSON 体**：它正是 #794 打坏的那个端点（登录全 403），它 CSRF 豁免（否则"少了 CSRF 的 403"与"错 origin 的 403"就分不出来），空体校验回 400——**400 恰恰证明请求到达了处理器，也就是 origin 被接受了**。403 判死
- `--smoke-origin` 带上 Origin 头——**证一个依赖配置的行为，比证一个与配置无关的 200 有价值**：origin allowlist 本身就是配置。默认目标会带上 allowlist 的第一项（即正确 origin），所以配置没加载时拿到的就是 403，**会被判死**而不是被当成 200 放过去
- **这些断言自身由 `deploy/tests/deploy_script_regression.py` 守着**（#818）：每个场景造一个**只有那一个缺陷**的栈，再问脚本有没有发现它——**在健康栈上通过不算证据，要在坏栈上失败才算**。CI 里是 `Deploy script (behavioural)` job。它做过**反向验证**：把三条修复分别改回去，harness 必须红（实测三次全部命中）。
- **自签证书的栈要加 `--smoke-insecure`**：冒烟的 curl 默认校验证书，自签时请求在 TLS 阶段就失败、状态码是 `000`，而 `000` 按设计只判 WARNING——**于是冒烟在这些部署上什么都证明不了，收尾语却照常是成功**。开关默认关（生产不该默认跳过校验）；打开后 `000` 的含义也跟着变清楚：不再是「证书可能有问题」，而就是「够不到」。文档里本机冒烟示例一直用 `curl -k`，脚本此前没有对应开关，属于两种默认不一致。
- **运维显式给了 `--smoke-url` 时方法仍是 GET**：对不是自己挑的目标强加 POST，就是又一条"比它检查的东西更严"的假阳性（#807 的教训）
- **不传 `--smoke-url` 时脚本会明说"什么都没查"**，收尾语也只说 `deployed; image identity verified`，不说 "verified"——**那个词曾经盖过了它实际没查的东西**
- **5xx/000 会重试：被换掉的容器还在启动**（#1038）。`up` 之后脚本会 `restart portal`，而刚被替换的 control-plane/gateway 还在冷启动（2G 演示机实测 Boot 约 9 秒）——冒烟若正好落在这个窗口，读到的是 nginx 的 502，**一次真实部署会因此报失败而箱子其实没病**。现在 `5xx` 与 `000` 在 `MIQROKEY_DEPLOY_SMOKE_RETRY_SECONDS`（默认 90）秒内每 2 秒重试，等待会打印 `smoke: not ready yet (502) — retrying…`，恢复后打印 `answered after N retries`；**4xx 一律立即分类**——403 是"答错了"，绝不能靠重试变成通过。

除了冒烟，脚本还会**逐条核对输入是否到达**：`.env` 里每个 `KEY=VALUE`，只要某个服务的容器里也有该 KEY，值就必须一致——不一致就**指名变量**（无需网络，也不需要"服务能应答"）。比的是**按 compose 的 dotenv 语义归一化后**的值：CR、首尾空白、一层引号都去掉。**这一层容错是必须的**——断言若比 compose 更严，它报的就是文件的行尾是否干净，而不是部署对不对（#807 那条假阳性正是如此：混合行尾的 `.env` 让两边打印出一模一样的字符串）。报错也会给出长度与字节转储，好让"看起来一样"的差异自己说清楚。

四条既有教训也编在里面，免得再踩：

- `up` 一律带 `--no-build`：`compose.prod.yaml` 的 control-plane 服务带 `build:` 段，且其 context 指向**线上树**——漏了它，compose 可能构建出不是本次要发的代码
- 替换 control-plane / gateway 之后**自动 `restart portal`**：nginx 的 upstream 是启动时解析的，后端容器换掉后地址变化，不重启则 `/api` 全 502
- **显式钉住 compose 项目名**（`-p`）：项目名取决于调用时的目录，从别处跑会**另起一套容器**而不是更新线上那套
- **`.env` 必需，且就在 compose 文件旁**（即上面第 3 条）

**每次执行追加一行到 `deploy.log`**（时间 / 模式 / 目标提交 / 调用方 / **env 文件路径** / **冒烟结果** / **compose 漂移** / 每个服务**运行中的镜像 ID 与当时的 tag ID**）。这一行是唯一的持久记录：等有人问"11:39 线上跑的是什么"，那张镜像可能早已被并发重建解除标签并从列表里清掉，届时只有这行还能回答。**两个身份都记**，是为了事后能分辨"tag 被人重建了"与"当初就没换过去"；`env_file=` 与 `smoke=` 则回答"当时用的是哪份配置、有没有查过功能"。

退出码：`0` 已部署、且断言与冒烟都通过 ｜ `1` 用法/环境（含 `.env` 缺失）｜ `2` 断言或冒烟失败 ｜ `3` 锁被占用（另一次部署在跑）。

### 8.2 运行树的 compose 归谁管：站点取值一律进 `.env`

`deploy.sh` 渲染的是**线上树**的 `$LIVE_DIR/deploy/compose.prod.yaml`，而每次发布带来的仓库版本在**构建树**里（`$CONTEXT/deploy/compose.prod.yaml`）。两者本应是同一份文件——线上树 = 某个提交的 `deploy/` + `secrets/` + `.env`。

**手改线上树的 compose 会怎样**：值对 git 不可见、不随任何提交走；整树同步（把构建树的 `deploy/` 覆盖上去）会**静默丢掉**它们；而在丢掉之前，没有任何东西能回答"这台机器跑的定义出自哪个提交"。2026-09-19 对齐演示站时就是人工把线上 compose 与某提交的版本逐行 diff，才复原出它实际在跑什么——漂移项包括 `shared_buffers` 硬编码、留痕消费端与响应缓存被写死为 `true`、派生列调和开关、`depends_on` 缺 #846 的编排等待、redpanda 少了 healthcheck 与 `profiles`。

**规则**：站点取值一律写 `.env`（`compose.prod.yaml` 用 `${VAR:-默认}` 透传），改 compose 文件本身只应该是"要进仓库的改动"。开关若在 compose 里**没有接线**，`.env` 设了也不生效（#777 的派生列调和就曾如此）——那属于仓库缺一行，应提 PR 补上，而不是在线上手改。

**部署脚本次次报告漂移**：比较两份 compose，不同即打 `WARNING`（**只警告、不判死**——运维可能正在迁移，且部署本身已在上面验证过），并把 `compose_drift=yes|no` 记进 `deploy.log`。这样"这台机器能否由某个提交复现"在每次部署时都有一次明确回答，而不是等到有人考古。

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


