# 编码规范

## 1. 总则

- 优先可读、可测试、可交接，不追求技巧性抽象。
- 领域名词使用 `domain-model.md` 中的统一名称。
- 一个模块只能依赖架构允许的下层模块。
- 业务规则放在 domain/service，不放在 Controller、Repository 或 Vue 组件。
- 禁止用 TODO 代替当前 Goal 的验收条件；延后事项必须进入 `progress.md`。

## 2. Java 模块依赖

```text
domain                 ← 无 Spring、无数据库、无 HTTP
provider-spi           ← domain
provider-adapters      ← provider-spi
persistence-postgres   ← domain
gateway-app            ← domain + provider-spi + provider-adapters + persistence port
control-plane-app      ← domain + provider-spi + provider-adapters + persistence-postgres
test-support           ← 测试 fixture，可被所有测试依赖
```

使用 ArchUnit 固化。`domain` 禁止依赖 `org.springframework.*`、JPA/R2DBC、Jackson HTTP DTO。

## 3. Gateway WebFlux

### 必须

- 使用 Reactor Netty `HttpClient` 或 Spring `WebClient` 的流式能力。
- 保持请求/响应背压与取消信号。
- 对 `DataBuffer`/`ByteBuf` 生命周期有明确测试。
- 所有缓冲区有上限。
- 阻塞工作切换到专用有界执行器，并记录队列指标。
- 超时、取消、首字节前重试必须在网络层集中实现。

### 禁止

- 在生产代码中调用 `.block()`、`.blockFirst()`、`.blockLast()`。
- 在业务方法内部裸 `.subscribe()`。
- 在 event loop 上使用 JDBC、`Files.*`、同步 DNS/HTTP、`Thread.sleep()`。
- 聚合完整流式响应后再发送。
- 为解析 usage 修改或重新序列化客户端请求体。
- 无界 `onBackpressureBuffer()`。

### 允许的阻塞边界

usage 批量写入、目录文件加载和加密密钥文件读取必须由专用组件管理，在启动或独立 executor 上执行。禁止随请求临时创建线程池。

## 4. Control Plane

Control Plane 使用 Spring MVC + Spring JDBC。理由见 ADR-0006。

- Controller 只处理 HTTP 映射、校验和 DTO。
- Service 负责事务和权限。
- Repository 使用明确 SQL 或 Spring JDBC，不引入隐藏 N+1 的 ORM 魔法。
- 所有列表 API 必须分页。
- 变更资源使用 `version` 乐观锁，冲突返回 409。
- 批量删除、导出、分摊走异步任务。

## 5. Java 风格

- Java 21；优先 record 表达不可变 DTO/value object。
- 枚举/密封接口表达有限状态，不用任意字符串。
- 金额使用 `BigDecimal` + ISO 4217 currency；禁止 double。
- Token 数使用 `long`，未知值使用 nullable/OptionalLong 语义，不以 0 代替。
- 时间使用 `Instant` 存储与传输；仅 UI 按 Asia/Shanghai 展示。
- ID 使用 UUID；外部可读 code/slug 另设唯一列。
- 公共方法参数明确校验；异常包含安全上下文，不包含 Secret/正文。
- 使用构造器注入；禁止字段注入。
- 使用 SLF4J 参数化日志；禁止字符串拼接 Secret。
- 格式由 Spotless 自动执行，不手工争论格式。

## 6. 错误处理

- 领域错误使用稳定 error code。
- 不能捕获后静默忽略。
- Gateway 上游错误尽量透明返回；Gateway 自身错误转换为入站协议兼容格式。
- 重试判断基于错误分类，不基于模糊字符串。
- 日志记录 gateway request ID、内部对象 ID 和安全错误摘要。

## 7. 数据库

- 所有 migration 使用 Flyway，已发布 migration 永不修改。
- 表和列使用 `snake_case`。
- 所有业务时间为 `timestamptz`。
- 所有租户业务表包含 `tenant_id`，即使首版只有一个租户。
- 外键默认显式指定删除行为；禁止随意 cascade 删除审计/流水。
- JSONB 只用于供应商扩展快照，不用于逃避稳定字段建模。
- 索引必须由真实查询和验收解释。
- 任何保存密钥的列禁止出现在通用 `toString()`、审计 diff 或 API DTO。

## 8. 前端

- Vue 3 Composition API 与 `<script setup lang="ts">`。
- TypeScript strict，不使用无解释的 `any`。
- Vue Router 管路由；Pinia 只保存会话和跨页面 UI 状态，不缓存服务端事实。
- API client 和类型由 OpenAPI 生成；手写 wrapper 只处理 CSRF、错误和分页。
- Element Plus 作为基础组件库，不直接修改其源码。
- 组件按 feature 组织，页面不直接拼接 API URL。
- 完整 Virtual Key 只存在创建/轮换结果组件内；离开页面后从 store 和内存引用清除。
- 敏感页设置禁止浏览器缓存；复制动作有明确成功/失败反馈。
- 普通用户 UI 不请求管理员数据，不能只靠隐藏组件做权限。

## 9. 测试

- 测试命名表达行为：`shouldRejectConflictingVirtualKeyHeaders`。
- 单元测试不启动 Spring；只有边界集成测试使用完整 context。
- Provider fixture 固定在仓库，不能调用真实网络。
- 时间使用可注入 Clock。
- 随机数使用可测试接口；生产实现使用 `SecureRandom`。
- 快照中禁止正文和 Secret。
- 每个 bug fix 必须先有能复现的测试。

## 10. 依赖与许可证

- 新依赖必须说明用途、许可证、替代方案和运行时影响。
- 生产依赖只允许 Apache-2.0、MIT、BSD 等已批准宽松许可证。
- 不引入未固定 Git dependency、动态版本、`latest` 镜像。
- 前后端 lockfile 必须提交。
- 能用 JDK/Spring 可靠实现的简单功能，不引入小众依赖。

## 11. Git 与提交

- 分支、commit、push、PR 和 tag 的完整规则以 [`git-workflow.md`](git-workflow.md) 为准。
- 一个 Goal 可以有多个小提交，但不得混入下一个 Goal。
- 不重写用户提交，不使用 destructive reset。
- 提交前运行受影响模块测试和格式检查。
- commit message 建议：`feat(gateway): proxy anthropic sse transparently`。

代码 comment/Javadoc 解释“为什么、约束和危险边界”，不逐行翻译实现。公开 Provider SPI、加密/透明代理的非显然约束需要 Javadoc；普通 getter、显然控制流不写噪音注释。`TODO` 必须带 Goal/Issue 或明确删除条件，注释中同样禁止 Secret 和客户正文。
