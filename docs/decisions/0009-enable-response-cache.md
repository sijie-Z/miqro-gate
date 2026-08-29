# ADR-0009：启用响应缓存（对齐腾讯云 AI 网关 L1 精确缓存方案）

- 状态：Accepted
- 日期：2026-08-29
- 关联：ADR-0003（v1 不做缓存）、ADR-0005（v1 不部署 Redis）、[腾讯云 AI 网关缓存策略文档](https://cloud.tencent.com/document/product/1826/134822)

## 决策

启用网关响应缓存（L1 精确缓存），取代 ADR-0003 的「v1 不做模型响应缓存」决定。采用腾讯云 AI 网关缓存策略的整体结构，按本项目架构约束本土化：

| 维度 | 腾讯方案 | 本项目落地 |
|---|---|---|
| 存储 | L1 用 Redis；L2 用向量库 VDB | **L1 用 PostgreSQL `cache_entry` 表**（V5 已建）；L2 语义缓存**不启用**（依赖向量库，违反 ADR-0005 约束，接口预留） |
| 缓存键 | 单轮问答「最新用户消息」/多轮「历史对话模式」 | 归一化请求 SHA-256（`CacheKeyFactory` 既有实现）；腾讯的键策略差异记录为后续优化项 |
| TTL | 60–604800 秒，默认 3600 | `miqrokey.cache.l1-ttl`，默认 300s |
| 命中标识 | `X-Cache: HIT/MISS`（高级配置，默认关） | 复用响应头 `X-MiQroKey-Cache-Hit`（既有实现） |
| 仅缓存成功 | 仅缓存 200 | 仅缓存 2xx（既有 `CacheEligibility`） |
| 开关 | 每模型 API 配置 | **双重 opt-in**（比腾讯更严）：Key `cachePolicy=ENABLED` **且** 客户端显式头 `X-MiQroKey-Cacheable: 1`；默认全关 |
| 命中统计 | 成本管理页：命中率/节省成本/明细 | `cache_hit_event` 表 + usage summary `savedByGatewayCache`（前端成本报表页展示） |

## 边界（隐私与安全）

- 缓存的响应字节只属于 `cachePolicy=ENABLED` 且客户端显式声明的 Key；正文不解读、不进日志、不进审计。
- 工具调用（body 含 tools 字段）永不缓存（工具结果副作用）。
- 缓存键含 tenant + Key 作用域，天然按用户隔离（对齐腾讯「按用户隔离缓存」高级配置，我们默认即隔离）。
- 客户端可用 `X-MiQroKey-Cacheable: 0` 或 Key 关闭策略随时退出。

## 原因

- 用户决策：缓存必须做（成本优化，对齐腾讯网关能力）。
- 腾讯文档实测数据：精确缓存命中延迟 <2ms，典型节省 30–60% 成本。
- 架构约束：不引 Redis/向量库（ADR-0005），复用既有 cache-spi（接口、Provider、重放引擎、eligibility 全部已实现并测试）。

## 后果

- `miqrokey.cache.enabled=true` 时网关装配真实缓存 Provider；默认仍关闭（生产默认零行为变化）。
- KeysView 创建/展示 cachePolicy；成本报表页展示缓存节省。
- 语义缓存（L2）维持禁用；未来若启用需新 ADR（向量库依赖）。
