# 系统架构

## 1. 总体原则

系统采用“控制面与数据面分离、同一 Java 代码库交付”的架构。

```text
真实供应商 API / Coding Plan / Token Plan / Team Plan
                         ↑
                Inference Gateway
          鉴权替换 · 透明代理 · 用量采集
                         ↑
                     CC Switch
          配置切换 · 协议转换 · 模型映射
                         ↑
       Claude Code / Claude Desktop / Codex

管理员/普通用户 → Vue Portal → Control Plane API → PostgreSQL
                                    ↓
                          供应商状态与 Webhook
```

CC Switch 位于客户端配置与本地转换层。运行时，客户端可能读取 CC Switch 写入的配置后直接请求 Gateway，也可能先经过 CC Switch 的本地代理；Gateway 对这两种情况保持一致。

## 2. 为什么不在 Gateway 做协议转换

CC Switch 已支持 Anthropic Messages、OpenAI Chat Completions、OpenAI Responses 和 Gemini 等格式之间的本地转换，并针对 Claude Code、Claude Desktop 和 Codex 维护兼容逻辑。Gateway 再做一次跨协议转换会：

- 重复 CC Switch 已有能力；
- 增加工具调用、推理字段和 SSE 事件损坏风险；
- 影响供应商 Prompt Cache；
- 使供应商适配数量与测试矩阵急剧扩大。

因此 Gateway 只理解协议以完成鉴权、路径、模型校验和 usage 解析，不改写推理语义。

参考：[CC Switch Claude Desktop 第三方供应商与本地路由](https://github.com/farion1231/cc-switch/blob/main/docs/user-manual/zh/2-providers/2.6-claude-desktop.md)。

## 3. 代码库建议结构

```text
miqro-key-gateway/
├── backend/
│   ├── pom.xml
│   ├── domain/                 # 纯领域模型和服务接口
│   ├── provider-spi/           # 供应商、协议、余额、模型目录 SPI（纯 Java + reactor-core，无 Spring/Jackson）
│   ├── provider-adapters/      # 签名目录加载与校验、编译期适配器注册、各供应商实现
│   ├── gateway-app/            # WebFlux 推理数据面
│   ├── control-plane-app/      # 管理、门户、导出、告警 API
│   ├── persistence-postgres/   # JPA/JDBC、Flyway、分区管理
│   ├── route-snapshot/         # 版本化只读路由快照（当前实现）
│   ├── queue-spi/              # 有界用量写入队列 SPI（当前实现）
│   ├── cache-spi/              # 响应缓存 SPI + NoOp 实现（当前实现）
│   └── test-support/           # Mock Provider 与契约测试工具
├── frontend/                   # Vue 3 + TypeScript
├── deploy/                     # Docker Compose、反向代理、备份
└── docs/
```

建议使用 Maven 多模块。`domain`、`route-snapshot`、`queue-spi`、`cache-spi` 和 `provider-spi` 不依赖 Spring，防止业务模型被框架绑定。

**当前实现的模块边界（G2.1/G2.2/G2.4）**：

- `provider-spi`：`com.miqroera.miqrokey.spi`——`ProviderProductAdapter` 契约及其值对象（`ProtocolFamily`、`ProviderProductDefinition`、`RouteContext`/`TargetRequest`、`CredentialMaterial`/`CredentialInjection`、`ProviderClient`、`UsageObserver`/`UsageObservation`、`PlanSnapshot`、`AdapterCapabilities`、`AdapterRegistry`）。核心 Gateway 只依赖此 SPI，禁止出现 `if (vendor == ...)` 分支。
- `provider-adapters`：`com.miqroera.miqrokey.adapters`——内置签名目录（Ed25519 校验 + 严格 schema 校验 + classpath 加载，`catalog/` 子包）与编译期适配器注册表（`registry/BuiltInAdapterRegistry`，重复 `adapterId` 启动失败）。目录是纯数据：任何未知字段（含代码类名字段）被 schema 拒绝，适配器解析只按 `adapterId` 走注册表，远程目录不可能加载代码。具体供应商适配器在 G3.x 加入 `providers/` 子包。

- `route-snapshot`：版本化只读快照——Gateway 在启动和定时刷新（默认 30s）时把 Virtual Key 摘要、Key→项目绑定、Grant 模型、项目标签、上游凭证密文加载为不可变快照；热路径零数据库访问，只做内存查询 + AES-256-GCM 解密。凭证解密后内存用完即清零。
- `queue-spi`：有界用量写入队列契约 + 内存实现（容量默认 10000）。Gateway 观察器只产生不可变事件（含幂等键 `provider_request_id`），专用调度器批量写 PostgreSQL；队列满不静默丢弃，写失败保留重试，`INSERT ... ON CONFLICT DO NOTHING` 防双计。
- `cache-spi`：`ResponseCache` 契约 + `NoOpResponseCache`。L1（Caffeine 风格内存）与 L2（PostgreSQL `cache_entry`）实现已存在但总开关默认关闭（ADR-0008）；只缓存 `cache_policy=ENABLED` 的 Key 且满足资格条件的响应，SSE 通过 `SseReplayEngine` 按字节重放。

## 4. 运行组件

### 4.1 Inference Gateway

职责：

- 从常见鉴权头中解析 Virtual Key。
- 查询 Virtual Key 的固定路由快照。
- 校验 Key 状态、项目成员关系、供应商产品和模型授权。
- 删除入站 Virtual Key，按供应商预设注入真实凭证。
- 重写必要的 Base URL 前缀，但不改变推理请求体语义。
- 透明转发普通响应与 SSE 流。
- 传播取消、超时和关键协议头。
- 从响应或 SSE 尾部提取 usage。
- 写入不可变用量事件。
- 提供按 Virtual Key 过滤的 `/v1/models`。

Gateway 使用 Spring WebFlux 与 Reactor Netty，热路径禁止阻塞式数据库和网络调用。凭证与路由配置以版本化只读快照加载；变更后由控制面发布刷新事件。单节点首版可使用进程内事件，未来多节点通过 PostgreSQL `LISTEN/NOTIFY` 或其他实现扩展。

### 4.2 Control Plane API

职责：

- 本地账号、登录会话和两级权限。
- 团队、项目、成员和项目授权。
- 供应商产品、Plan、真实凭证和模型目录。
- Virtual Key 创建、轮换、吊销。
- 统计查询、异步导出、手动删除。
- Webhook、告警规则、余额和 Plan 周期快照。
- 管理员审计日志。
- 签名供应商目录更新。

Control Plane 使用 Spring MVC + Spring JDBC。响应式网络模型只用于 Gateway；控制面不引入 R2DBC。两者共享领域模块但作为独立应用运行。

### 4.3 Vue Portal

同一前端根据角色展示管理员或普通用户页面。门户不承担任何密钥加密逻辑，所有敏感操作由后端完成。

### 4.4 PostgreSQL

PostgreSQL 是唯一首版状态存储：

- 账号、项目、授权和凭证元数据；
- 加密后的真实凭证；
- Virtual Key 摘要；
- 用量流水和统计；
- 告警状态、定时任务锁、导出任务；
- 审计日志。

第一版不部署 Redis。未来缓存实现通过 SPI 加入，不影响核心模型。

## 5. 请求时序

```text
Client/CC Switch        Gateway          PostgreSQL snapshot       Upstream
       |                   |                       |                    |
       | request + VK      |                       |                    |
       |------------------>| parse/verify VK       |                    |
       |                   | resolve fixed route   |                    |
       |                   | replace auth          |                    |
       |                   |------------------------------------------->|
       |                   |                       |     SSE/response   |
       |<------------------|<-------------------------------------------|
       |                   | parse usage without content logging        |
       |                   |---- append usage event ------------------->|
```

用量事件写入失败不能无提示丢失。首版采用本地有界缓冲加数据库批量写入；缓冲达到上限时 Gateway 应发送高优先级告警，并可切换为同步写入以保护审计完整性。

## 6. 超时与重试

- 建立上游连接：10 秒。
- 等待首个响应内容：120 秒。
- 流式响应空闲超时：5 分钟。
- 整体请求默认不设置短硬截止，管理员可配置最大值。
- 客户端断开时立即取消上游请求。
- 只有在尚未向客户端返回任何内容时，连接失败或明确可重试错误最多重试一次。
- 流式响应一旦开始，禁止重试。
- 禁止跨供应商或跨真实凭证故障切换。

## 7. 缓存策略

首版不缓存模型响应。Gateway 必须原样保留供应商 Prompt Cache 所依赖的：

- 请求体顺序和内容；
- `cache_control` 等协议字段；
- Anthropic beta 头；
- Responses API 的缓存与会话字段；
- 上游返回的 cache read/write Token。

后续通过 `GatewayResponseCache` SPI 增加精确缓存或其他实现。Claude Code、Claude Desktop、Codex 和工具调用默认禁用 Gateway 响应缓存。

## 8. 可扩展接口

```java
public interface ProviderProductAdapter {
    CredentialInjector credentialInjector();
    PathPolicy pathPolicy();
    ModelCatalogProvider modelCatalogProvider();
    UsageParser usageParser();
    PlanStatusProvider planStatusProvider();
    CredentialValidator credentialValidator();
}

public interface GatewayResponseCache {
    CacheLookupResult lookup(CacheKey key);
    void store(CacheKey key, CacheableResponse response);
}
```

接口表达职责即可，具体签名在实现阶段通过 ADR 固化。

## 9. 技术栈

- Java 21。
- Spring Boot 3.x。
- Spring WebFlux / Reactor Netty。
- Spring Security。
- PostgreSQL 与 Flyway。
- Vue 3、TypeScript、Vite。
- Testcontainers、WireMock 或自研 Reactor Mock Provider。
- Micrometer Prometheus。
- Docker Compose。

精确依赖版本在创建代码骨架时锁定，并由 Dependabot/Renovate 类工具提出升级 PR，禁止运行时自动拉取最新版依赖。
