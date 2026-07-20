# 测试与验收

## 1. 测试原则

- 自动化测试不依赖真实收费接口。
- 真实供应商测试必须显式启用，Key 通过 Secret 注入。
- “支持”分为官方资料、代码实现和真实验证三个层级。
- 透明代理的核心指标是“不改变客户端和供应商之间的协议语义”。

## 2. 单元测试

- Virtual Key 生成、摘要、常量时间校验和一次性展示。
- AES-GCM 加解密、AAD、密钥版本和轮换。
- 用户/项目/Grant 权限矩阵。
- ProviderProduct 路径、鉴权和模型策略。
- 各协议 usage 解析，包括缺字段和未知字段。
- Plan 周期、团队共享池、席位和成本摊销。
- 告警阈值与 Webhook 签名。
- 原始流水导出字段脱敏。

## 3. 协议透明契约测试

Mock Provider 捕获 Gateway 上游请求并断言：

- 除鉴权、Host、路径前缀和必要 hop-by-hop 头外，请求字段保持一致；
- 未识别 JSON 字段被保留；
- Anthropic beta、cache_control、thinking、tool use/result 保留；
- Responses item、function call、reasoning 和 SSE 顺序保留；
- OpenAI Chat 的 reasoning_content、tools 和 SSE 保留；
- 上游错误状态和响应体被正确返回；
- Virtual Key 不会发送到上游；
- 客户端取消会取消 Mock Provider 连接。

## 4. 流式测试

- UTF-8 多字节字符跨 chunk；
- SSE 注释、空行、多 data 行；
- usage 只出现在最终事件；
- 上游中途断开；
- 客户端中途断开；
- 首字节前一次安全重试；
- 首字节后绝不重试；
- 慢客户端背压；
- 50 条并发流。

## 5. 缓存保护测试

虽然首版不做响应缓存，仍需验证：

- 同一 Virtual Key 总是使用同一真实凭证版本；
- `cache_control` 和缓存相关头不丢失；
- 请求体不被格式化、排序或注入内容；
- cache read/write Token 正确记录；
- 真实凭证轮换后仅新请求切换。

## 6. `/v1/models` 测试

- 上游模型列表、内置目录、项目授权和 Key 快照正确求交集；
- 上游不可用时使用最后成功目录或内置目录；
- 新模型不会自动越过项目授权；
- 普通用户不能看到其他产品模型；
- 返回格式可被 CC Switch 解析。

## 7. CC Switch 端到端验收

每个已验证供应商产品至少执行：

| 场景 | 客户端 | 必测内容 |
|---|---|---|
| Anthropic 透明产品 | Claude Code | 对话、tools、thinking、缓存、取消、长会话 |
| 第三方格式产品 | Claude Code + CC Switch Local Routing | 转换后请求透明转发、模型 ID、usage |
| Direct/Mapping | Claude Desktop | `/v1/models`、重启生效、角色映射 |
| Responses | Codex | 流式 item、function call、reasoning、取消 |

门户只提供 Base URL 与 Virtual Key，测试人员手工创建 CC Switch Provider，与真实用户流程一致。

## 8. 真实供应商契约测试

通过环境变量或 Secret 注入 Key，测试日志仅保留 request ID 和脱敏结果。每个适配器验证：

- Key 合法/非法；
- 模型列表；
- 普通与流式推理；
- 工具调用；
- usage 与缓存 Token；
- 余额/周期状态；
- 上游 request ID；
- Plan 专属 Base URL 确实消耗对应套餐；
- 团队 Plan 的成员 Key/共享池行为。

没有真实 Key 的适配器标记“已实现、待实测”。

## 9. 安全测试

- 普通用户水平/垂直越权；
- SSRF 与任意路径访问；
- 多鉴权头冲突；
- 日志和导出 Secret 扫描；
- 数据库备份中密文与主密钥分离；
- Session、CSRF、登录锁定；
- 恶意超大 JSON、超长头和慢速连接；
- 依赖、容器和 SBOM 扫描。

## 10. 性能验收

在本地 Mock Provider 下：

- 50 个账号数据规模；
- 50 条并发 SSE；
- Gateway 额外首包延迟 P95 不超过 30ms；
- 无明显事件循环阻塞；
- 内存有界且连接释放正常；
- 用量写入不丢失；
- 导出任务不显著影响推理延迟。

## 11. 备份恢复验收

- 自动备份成功并生成校验值；
- 备份失败产生 Webhook；
- 新 PostgreSQL 实例可恢复；
- 使用独立保存的主密钥可解密真实凭证；
- Virtual Key 仍可验证；
- 原始流水和审计数量一致。

## 12. 发布门槛

- 单元、集成、协议和安全测试通过。
- Linux 镜像构建和 Compose 冒烟通过。
- SBOM 与许可证检查通过。
- 数据库迁移和恢复测试通过。
- 至少一个个人 Plan、一个团队 Plan、一个按量 API 完成真实端到端验证后，首个生产版本才可发布。

