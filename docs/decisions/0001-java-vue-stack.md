# ADR-0001：Java 与 Vue 技术栈

- 状态：Accepted
- 日期：2026-07-17

## 决策

后端采用 Java 21、Spring Boot 3、Spring WebFlux；前端采用 Vue 3 与 TypeScript；PostgreSQL 为首版唯一状态存储。

## 原因

客户现有维护能力集中在 Java 与 TypeScript/Vue。使用同一技术栈可以完整交付源码、降低培训与长期维护成本。WebFlux/Reactor Netty 适合长连接 SSE、取消传播和有界背压。

## 后果

- 需要自行实现协议透明代理和供应商适配器。
- Bifrost 等 Go 项目只能作为行为参考和测试对照。
- 开发必须特别关注 Reactor 阻塞检测和流式资源释放。

