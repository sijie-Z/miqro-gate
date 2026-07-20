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

