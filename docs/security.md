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
- 入口（Nginx）对所有响应下发四个安全头（`always`，含错误响应）：`Strict-Transport-Security: max-age=31536000; includeSubDomains`、`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: strict-origin-when-cross-origin`。**HSTS 刻意不含 `preload`**——提交浏览器 preload 列表的撤回以月计，内网/私有化部署不值得锁死这条退路。这个头是 2026-09-20 全站验收扫出来的：仓库里此前**任何地方**都没有它（Nginx、Spring、文档全无），而响应里唯一见过它的是上游透传（DeepSeek 边缘会发 HSTS），属"看起来有"的假象——验收若只打一次推理请求就会被它骗过。
- 容器端口仅位于 Compose 内部网络，由 Nginx、Caddy 或客户现有入口终止 TLS。
- 本地开发允许 `localhost` HTTP。
- 管理门户支持配置 IP 白名单（F05 已实现）：`miqrokey.control.admin-access.ip-allowlist`（CIDR 列表，空 = 不限制）。配置后门户面（会话 UI/API）仅名单内来源可访问，其余 403 `IP_NOT_ALLOWED`；`/api/v1/billing/**` 外部系统通道与 `/api/v1/auth/bootstrap` 一次性引导豁免（IP 名单语义是"人用浏览器管门户"，机器通道走自己的凭证体系）。反代部署配 `...trusted-proxies`（CIDR）：只有来自受信代理的 `X-Forwarded-For` 被采纳，直连来源无法伪造头绕过。非法 CIDR 配置导致启动失败。
- 推理 API 默认不限制来源 IP，以免影响远程开发；可作为后续可选策略。
- 上游目标主机只能来自签名供应商目录，禁止用户输入任意 URL，防止 SSRF。
- 所有上游目标经 `UpstreamTargetValidator`（G2.6）双重门控后才会建立连接：
  - **协议**：`https` 是硬要求；携带 `userinfo` 的 URL 一律拒绝（凭证走私）。
  - **已解析地址**：DNS 解析后的每个地址必须是公网地址；环回、链路本地（含 `169.254.169.254`）、RFC1918、IPv4 CGNAT `100.64/10`、组播、any-local 与 IPv6 ULA `fc00::/7` 全部拒绝。
  - 非公网目标或明文 http 仅在命中 `MIQROKEY_UPSTREAM_ALLOWED_CIDRS` 时放行（受信任自建模型的显式逃生口，生产默认空 = 全拒）。
  - 校验在专用调度器上执行（DNS 为阻塞调用，不占事件循环）；拒绝原因只有稳定类别 token（`non-https`/`non-public-address`/`userinfo-forbidden` 等），错误响应、日志与审计永不出现目标 URL。
- 入站防护（G2.6）：Header 超过 `32KB` 由 Netty 在路由前拒绝（`431`）；请求体超过 `256KB` 缓冲上限 → `413`；数据面仅暴露三个 `POST` 路径，其余 `/v1/**` → `404`，错误方法 → `405`，均不触达上游。
- 请求前置预检（#553）：鉴权与模型授权通过后、缓存查询与上游调用之前，按 UTF-8 码点统计整个已缓冲 body 的字符数（合法 UTF-8 下为字符上界；非法 UTF-8 序列整段按字节长度计，计数整体不低估，且仍受 256KB 缓冲上限约束），超过 `MIQROKEY_GATEWAY_CONTEXT_LIMIT_THRESHOLD_CHARS`（默认 `200000`）→ `413 context_limit_exceeded`，不连接上游（阈值是防失控上下文的安全阀、不是配额；启用后 200001–262144 字符的请求由「缓冲上限放行」变为 413，属刻意收紧）。该判定只读字节、不解析/不重写 body（转发字节不变），日志与错误体只出现字符数与阈值，不含请求内容；命中计数 `miqrokey_gateway_context_limit_rejected_total`（零标签）。`MIQROKEY_GATEWAY_CONTEXT_LIMIT_ENABLED=false` 可整体关闭。
- 错误脱敏（G2.6）：数据面错误体不含目标 URL、主机名、真实凭证或 Virtual Key；未知路径与鉴权失败不区分原因，防枚举。
- 上游错误体分类（ADR-0024 选项 B / #770，只观测）：网关对**已缓冲**的上游非 2xx 响应体做**有界只读**分类（前 8KB 子串匹配），只产出**枚举类别**的计数与 `status=… class=…` 一行日志；**不重试、不改写请求、不改变响应**；错误正文不进日志、不落库、不进事件；缓冲被截断时不分类。

## 7. 日志与隐私

默认不保存：

- prompt、代码片段、图片、文件；
- 工具参数与工具结果；
- 模型回答；
- 完整请求/响应头；
- 任何完整密钥。

保存 usage、路由元数据、状态、延迟和脱敏错误。若未来增加调试正文日志，必须单独设计显式开关、脱敏、短保留期和审计；不属于首版。

### 7.1 派生数据的三层口径

对「不保存正文」这句承诺，本产品按三层表述（ADR-0022 §6，2026-09-19 拍板采纳）。三层缺一不可，引用时请一并引用：

1. **默认不持久化**：prompt、代码、工具正文与模型回答（见上方列表）。响应缓存是**精确匹配**——只存响应字节、不解读正文、不进日志与审计（ADR-0009）。
2. **派生数据单独披露**：启用特定缓存或检索能力时，系统可能产生由请求内容**派生**的数据（指纹、向量等）。此类数据的**处理位置、是否持久化、保留期限与隔离范围**，在对应功能的说明中单独披露，不并入第 1 条，也不由第 1 条覆盖。
3. **默认不出环境**：默认不将请求正文发送至任何第三方服务。任何会改变这一点的能力（例如把正文送往外部 embedding / 向量服务）都必须先经 ADR 审议并修订本节。

**当前状态**：第 2 条所指能力**均未启用**。语义缓存的离线评测（ADR-0022 §11 P1 阶段一）用全合成数据、不接触任何生产正文；若将来落地进程内的影子测量或向量化能力，将按第 2 条在本节登记其处理位置与保留方式。

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


## 11. 开放管理面（机器密钥，ADR-0015，2026-09-07 增补）

- 凭据：256-bit 随机、展示前缀 `mqk_admin_`，库中仅存 SHA-256 摘要（bytea）；明文发行时仅展示一次；
  可设过期、可吊销（吊销即时生效——鉴权过滤器逐请求查库校验）。
- 传输与豁免：`Authorization: Bearer mqk_admin_…`；SessionFilter 对 `/api/v1/admin-api/**` 豁免会话
  要求（同 billing 机器通道先例），由 AdminApiKeyAuthFilter(-95) 自证——机器通道不走门户白名单路径，
  无 CSRF（非 cookie 承载；读面全 GET）。
- 会话规则：门户会话访问开放面仅限 SYSTEM_ADMIN（403 `ADMIN_API_FORBIDDEN`），会话租户即开放面租户
  （与机器密钥同一请求属性契约）。
- 范围与边界：读子集为租户级只读（usage/audit/api-keys/quota-rules/export-tasks 元数据/mcp 日志）；
  写面已在 ADR-0016（机器执行者语义，2026-09-08 Accepted）下按批开放——批 2 v1 含告警规则与 Webhook
  端点全生命周期（候选 C）与导出创建（候选 A，`created_by` 记发行管理员）；导出文件字节永不进入机器面。
- 审计：机器**写**操作全部进既有哈希链审计（动作命名空间 `ADMIN_API_KEY_*` 等），跨租户不可见，可沿链回到
  「哪把密钥」→「谁发行」（ADR-0016 的可追溯性承诺，口径即「任何机器写操作」）。**有意不进审计的三类**
  （不是缺陷，详见 issue #1409）：① 成功的只读调用；② 凭据缺失/无效的 401（`ADMIN_API_KEY_INVALID`）——
  此时没有可归属的租户，而 `admin_audit_events.tenant_id` 是 `NOT NULL REFERENCES tenants(id)`；
  ③ 门户非 SYSTEM_ADMIN 会话打开放面的 403（`ADMIN_API_FORBIDDEN`）。越权拒绝（`ADMIN_API_KEY_SCOPE_DENIED`）
  **是**留痕的。若要连只读调用一并审计，属于扩大审计面的新决策，需新 ADR 与容量/留存评估。

## 供应链与发布门禁（G6.3）

| 门禁 | 位置 | 说明 |
|---|---|---|
| Secret 扫描 | `deploy/security/check-secrets.sh` | `git grep` 高信号凭证模式（sk-/Bearer/AKIA/xox/ghp_）；放行构建产物、测试夹具与 compose 占位符；**文档示例 Key 一律打码**（G6.3 已清理 23 处） |
| 许可证门禁 + SBOM | `deploy/security/check-sbom.sh` | CycloneDX 聚合 BOM（gateway + control-plane 运行时依赖）；拒绝 GPL/LGPL/AGPL/SSPL/EPL/MPL/CC-BY-NC/SA |
| 镜像扫描 | CI `security` job | Trivy 扫 compose 固定 digest 镜像（postgres 17.11-alpine @sha256:18cfe3ef…，2026-08-13 官方构建、17 线最新 patch；标签原写 17.6-alpine 系陈旧，digest 内容即 17.11），HIGH/CRITICAL 未修复即失败；`deploy/security/.trivyignore.yaml`（2026-09-07 由文本格式迁移）逐条豁免并附理由、按 purl 限定包、可设 `expired_at`：openssl QUIC 不可达路径、gosu/golang 打包工具本地 DoS、**util-linux 7 项临时豁免（2026-10-07 硬到期，官方 alpine 重建后移除）**；`postgres-image-update` workflow 每日对比官方 `17-alpine` digest，上游重建自动开升级 PR、实扫通过即自动剥离 util-linux 临时集 |
| 目录签名 | 既有（G2.1） | provider-catalog.json Ed25519 签名 + 启动强校验，篡改即启动失败 |
| 审计完整性 | 既有（G2.3） | admin_audit_events 哈希链（previous/current_event_hash + chain_position），链断裂测试覆盖 |

CI：`security` job 在每次 push/PR 运行全部门禁；compose job 继续强制 digest 固定。
