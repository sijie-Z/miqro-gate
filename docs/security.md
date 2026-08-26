# 安全设计

## 1. 威胁重点

系统集中保存多个供应商的真实 API Key，最重要的风险是：

- 数据库或备份泄漏导致真实 Key 暴露；
- 管理员账号被接管；
- Virtual Key 在日志、错误或上游请求中泄漏；
- 供应链依赖被植入恶意代码；
- 普通用户越权查看其他人或 Plan 总量；
- 导出文件包含敏感字段；
- 代理开放任意 URL 形成 SSRF。

## 2. 真实凭证加密

- 使用 AES-256-GCM，每个密文使用独立随机 nonce。
- 附加认证数据绑定 Credential ID、租户和密钥版本。
- 主密钥通过 Docker Secret 或只读挂载文件注入，不写入数据库、镜像或仓库。
- 数据库只保存密文、版本和非敏感指纹。
- `KeyEncryptionProvider` 抽象支持将来接入云 KMS。
- 支持主密钥轮换：新写入使用新版本，后台分批重新加密旧密文。
- 旧主密钥在全部迁移并验证备份可恢复前不能删除。

## 3. Virtual Key

- 使用加密安全随机数生成至少 256 bit secret。
- 完整明文仅返回一次。
- 数据库保存 HMAC-SHA-256 摘要，不保存可恢复密文。
- HMAC pepper 与数据库分离，通过 Secret 注入。
- 比较使用常量时间算法。
- 日志、错误、指标标签只使用 Key ID、前缀和末四位。

## 4. 门户账号

首版使用本地账号，不接 OIDC/LDAP，不启用 MFA。

补偿措施：

- 密码使用 Argon2id；
- 管理员创建临时密码，首次登录强制修改；
- 强密码与已泄漏常见密码检查；
- 登录失败指数延迟和临时锁定；
- 不在日志中记录密码或完整用户名密码请求；
- 使用服务端会话或可立即撤销的安全会话；
- Cookie 设置 `HttpOnly`、`Secure`、`SameSite`；
- 所有状态变更请求启用 CSRF 防护；
- 管理员关键操作要求再次输入当前密码可作为实现阶段安全增强。

## 5. 授权

只有两种角色：`SYSTEM_ADMIN` 和 `USER`。

- 后端每个资源查询必须包含所有权或管理员断言，不能只依赖前端隐藏。
- 普通用户不能通过修改 ID 查看其他用户的 Key、统计或项目汇总。
- Inference Gateway 以 Virtual Key 固定快照授权，不接受客户端传入项目或真实凭证 ID。
- `/v1/models` 必须按 Virtual Key 过滤。

## 6. 网络

- 生产环境只通过 HTTPS 暴露。
- 容器端口仅位于 Compose 内部网络，由 Nginx、Caddy 或客户现有入口终止 TLS。
- 本地开发允许 `localhost` HTTP。
- 管理门户支持配置 IP 白名单。
- 推理 API 默认不限制来源 IP，以免影响远程开发；可作为后续可选策略。
- 上游目标主机只能来自签名供应商目录，禁止用户输入任意 URL，防止 SSRF。
- 所有上游目标经 `UpstreamTargetValidator`（G2.6）双重门控后才会建立连接：
  - **协议**：`https` 是硬要求；携带 `userinfo` 的 URL 一律拒绝（凭证走私）。
  - **已解析地址**：DNS 解析后的每个地址必须是公网地址；环回、链路本地（含 `169.254.169.254`）、RFC1918、IPv4 CGNAT `100.64/10`、组播、any-local 与 IPv6 ULA `fc00::/7` 全部拒绝。
  - 非公网目标或明文 http 仅在命中 `MIQROKEY_UPSTREAM_ALLOWED_CIDRS` 时放行（受信任自建模型的显式逃生口，生产默认空 = 全拒）。
  - 校验在专用调度器上执行（DNS 为阻塞调用，不占事件循环）；拒绝原因只有稳定类别 token（`non-https`/`non-public-address`/`userinfo-forbidden` 等），错误响应、日志与审计永不出现目标 URL。
- 入站防护（G2.6）：Header 超过 `32KB` 由 Netty 在路由前拒绝（`431`）；请求体超过 `256KB` 缓冲上限 → `413`；数据面仅暴露三个 `POST` 路径，其余 `/v1/**` → `404`，错误方法 → `405`，均不触达上游。
- 错误脱敏（G2.6）：数据面错误体不含目标 URL、主机名、真实凭证或 Virtual Key；未知路径与鉴权失败不区分原因，防枚举。

## 7. 日志与隐私

默认不保存：

- prompt、代码片段、图片、文件；
- 工具参数与工具结果；
- 模型回答；
- 完整请求/响应头；
- 任何完整密钥。

保存 usage、路由元数据、状态、延迟和脱敏错误。若未来增加调试正文日志，必须单独设计显式开关、脱敏、短保留期和审计；不属于首版。

## 8. 导出安全

- 仅管理员可导出。
- 文件不包含明文 Key 或正文。
- 临时下载地址短期有效。
- 导出创建、下载和删除都写入审计。
- 文件生成目录不得由 Web 服务器直接遍历。

## 9. 供应链

2026 年 3 月 LiteLLM 的 PyPI 版本 `1.82.7`、`1.82.8` 曾遭供应链攻击并包含凭证窃取代码。由于本系统集中持有供应商密钥，决定不依赖 LiteLLM 运行时。参考：[PyPI 事故报告](https://blog.pypi.org/posts/2026-04-02-incident-report-litellm-telnyx-supply-chain-attack/) 与 [LiteLLM 官方说明](https://docs.litellm.ai/blog/security-update-march-2026)。

发布要求：

- 生产依赖仅允许 Apache-2.0、MIT、BSD 等宽松许可证。
- 锁定 Maven、npm 和容器依赖版本及校验值。
- 禁止 `latest` 镜像标签。
- 生成 CycloneDX SBOM 与第三方许可证清单。
- CI 执行依赖漏洞、恶意包和 Secret 扫描。
- 供应商目录使用离线公钥验证签名。
- 远程目录只能更新数据，不能下载执行 Java/JavaScript 插件。

## 10. 审计

管理员审计永久保存，包含：登录安全事件、账号、授权、凭证、目录、Virtual Key、告警、导出、删除、加密密钥轮换和部署配置变更。审计事件使用追加写入模式，并定期生成链式哈希或批次校验值用于检测篡改。


## 供应链与发布门禁（G6.3）

| 门禁 | 位置 | 说明 |
|---|---|---|
| Secret 扫描 | `deploy/security/check-secrets.sh` | `git grep` 高信号凭证模式（sk-/Bearer/AKIA/xox/ghp_）；放行构建产物、测试夹具与 compose 占位符；**文档示例 Key 一律打码**（G6.3 已清理 23 处） |
| 许可证门禁 + SBOM | `deploy/security/check-sbom.sh` | CycloneDX 聚合 BOM（gateway + control-plane 运行时依赖）；拒绝 GPL/LGPL/AGPL/SSPL/EPL/MPL/CC-BY-NC/SA |
| 镜像扫描 | CI `security` job | Trivy 扫 compose 固定 digest 镜像（postgres 17.6-alpine），HIGH/CRITICAL 未修复即失败；`deploy/security/.trivyignore` 记录镜像内 golang 工具链本地 DoS CVE（非 postgres 服务攻击面），**镜像升级时移除豁免** |
| 目录签名 | 既有（G2.1） | provider-catalog.json Ed25519 签名 + 启动强校验，篡改即启动失败 |
| 审计完整性 | 既有（G2.3） | admin_audit_events 哈希链（previous/current_event_hash + chain_position），链断裂测试覆盖 |

CI：`security` job 在每次 push/PR 运行全部门禁；compose job 继续强制 digest 固定。
