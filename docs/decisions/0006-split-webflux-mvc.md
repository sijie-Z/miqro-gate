# ADR-0006：Gateway WebFlux，Control Plane MVC

- 状态：Accepted
- 日期：2026-07-17

## 决策

`gateway-app` 使用 Spring WebFlux/Reactor Netty；`control-plane-app` 使用 Spring MVC + Spring JDBC。两者共享领域与供应商模块，但独立运行和部署。

## 原因

Gateway 需要 SSE、背压和取消传播；Control Plane 主要是事务型 CRUD、导出任务和 PostgreSQL，MVC/JDBC 更简单且更符合客户 Java 维护经验。强行使用 R2DBC 会扩大认知和实现成本。

## 后果

- Gateway 不能在 event loop 上执行 JDBC；usage 写入使用专用有界 executor。
- 两个应用可以独立扩容和升级。
- 同一代码库需要分别维护 reactive 和 synchronous 边界测试。

