# ADR-0012：Kafka 引入评估（草案，待所有者拍板）

- 状态：**Proposal（草案）**——未决策，等待项目所有者/leader 确认场景与拓扑
- 日期：2026-09-04
- 关联：[feature-backlog F34](feature-backlog 见 ../feature-backlog.md)（Kafka 引入）、F32（平台用户同步）、ADR-0002（透明代理）、ADR-0006（WebFlux/MVC 边界）、[queue-spi 架构](../architecture.md)（usage 有界队列）、[platform-middleware-roadmap.md](../platform-middleware-roadmap.md)

## 背景与事实

- Leader 方向（2026-08-28 转述）：「Kafka 这个技术我们一定会用到」。仅方向，场景未细化。
- 本仓库现状（无 Kafka）：
  - 路由快照刷新：PostgreSQL `LISTEN/NOTIFY`（G2.x，单实例语义）。
  - usage 落库：有界内存队列 + `PostgresUsageEventWriter`（`queue-spi` 接口化，
    InMemory/Postgres 两实现；饱和默认丢弃 + F35 应急直写）。
  - 告警/审计：Webhook 签名投递（指数退避）＋审计哈希链（PostgreSQL 事务内）。
  - 消费/计费通道：消费者 API Key/JWT（ADR-0010/0011）对外只读查询，无推送。
- 云厂商参照（本次调研）：
  - 阿里云百炼：异步任务完成通知走**事件总线 EventBridge**（default 总线），
    目标支持 HTTP 回调或 **RocketMQ**；不直接内建 Kafka 网关通道。
  - 腾讯 AI 网关：事件驱动告警/通知经 Webhook（本仓库 F03 已实现同语义）；
    研究中无 Kafka 原生用法。
  - 结论：两家云的「网关内事件」以事件总线/Webhook 形态暴露；Kafka 属于
    **自有基础设施**选型，场景由架构需求（而非云产品）驱动。

## 候选引入场景（需选定，不做全押）

1. **usage/审计事件流导出**：把 usage_event/审计事件扇出到 Kafka topic，供外部
   消费（计费对账、数据平台、报备合规审计）；PostgreSQL 仍为主存储。
2. **跨实例/多副本一致**：私有化多实例部署后 NOTIFY/内存队列的横向语义；
   若单实例不变，此场景不成立。
3. **平台用户同步（F32）**：外部平台 user_id ↔ 本网关账号同步事件流
   （用户要求"外部平台组测的用户这边也要有"）——同步本体可 REST/JWT 先行，
   Kafka 只做事件通知面。
4. **告警/审批等事件的统一投递总线**：把 Webhook 退避投递前移为 Kafka 消费
   （改动面大，不推荐先行）。

## 决策点（草案建议，全部待 owner 拍板）

- **范围**：只对场景 1（导出流）或场景 3（同步事件）之一开 Kafka 最小闭环，
  其余保持现状；不做「替换 usage 写路径」。
- **拓扑**：单 broker 起步（KRaft），topic 按事件族划分；序列化 JSON（与现有
  payload 一致）或 Avro 待定；消费方鉴权沿用控制面凭据体系。
- **queue-spi 边界**：新增 `KafkaUsageEventPublisher` 只做**扇出**，`PostgresUsageEventWriter`
  保持主写路径；失败降级不阻塞请求（对齐 F35 语义）。
- **F32 同步本体**（独立于 Kafka）：建议先在 ADR-0011 的 JWT `sub` 映射上加
  「平台 user_id ↔ 用户」显式映射列（表 V29+），REST 同步接口先行，Kafka 仅承载
  变更通知——这一步不受 Kafka 决策阻塞。

## 草案建议的下一步

1. 所有者确认场景候选（1/3/2）与是否单 broker 私有化部署形态；
2. leader 若有场景文档，以其为准更新本 ADR；
3. 批准后拆两个 Goal：F32 用户映射/同步（可先行）+ Kafka 最小扇出（Kafka 引入 ADR
   定稿为 Accepted 后实施）。

## 未决（BLOCKED 项维持）

- leader 对 Kafka 具体用法的补充说明未到；本 ADR 的 Accepted 化等待
  上述决策点答复。在此之前不改动 `queue-spi`/部署拓扑。
