# 开发路线图

## 阶段 0：技术骨架与风险 PoC

目标：在完整业务开发前证明 Java WebFlux 数据面可行。

- 创建 Maven 多模块和 Vue 工程。
- PostgreSQL、Flyway、Compose、Windows 开发脚本。
- 实现最小 Virtual Key 校验和固定上游转发。
- 完成 Anthropic Messages 与 OpenAI Responses SSE 透明代理 PoC。
- 用 CC Switch 实测 Claude Code 与 Codex。
- 验证取消、工具调用、usage 和缓存字段。

退出条件：透明代理契约通过，额外首包延迟满足目标。

## 阶段 1：身份、项目和密钥

- 本地账号与两级角色。
- 团队、项目、成员、ProjectProviderGrant。
- Virtual Key 自助创建、一次展示、轮换、吊销。
- 真实凭证 AES-GCM 加密、验证和无感轮换。
- 管理员审计。

退出条件：一个真实 Key 可安全映射为多个用户/项目 Virtual Key。

## 阶段 2：透明代理生产化

- 多鉴权头兼容与出站凭证注入。
- ProviderProduct 路径与协议策略。
- Anthropic Messages、OpenAI Responses、OpenAI Chat 透明解析。
- `/v1/models`。
- SSE、背压、超时、安全重试和取消。
- usage 旁路解析与可靠写入。

退出条件：CC Switch 下 Claude Code、Claude Desktop、Codex 主流程通过。

## 阶段 3：首批供应商与 Plan

按真实 Key 可获得性分批实现 P0：

1. DeepSeek 按量 API，作为基础端到端样板。
2. 腾讯 TokenHub 个人/企业产品。
3. 智谱个人/团队 Coding Plan。
4. MiniMax 个人/团队 Token Plan。
5. 阿里百炼 Coding Plan/Token Plan 团队版。
6. Kimi Code 与按量 API。
7. 百度千帆 Plan。
8. 火山方舟 Plan。

每个产品独立发布验证状态，不等待全部完成才合并。

## 阶段 4：统计、成本和告警

- 管理员和个人仪表盘。
- 原始流水、Token 权威等级和价格快照。
- Plan 余额/周期快照。
- 团队共享池、席位与成员 Key。
- 固定订阅成本和内部项目摊销。
- Webhook 与六类告警。
- CSV/JSONL 导出和永久保留/手动删除。

## 阶段 5：交付硬化

- HTTPS 反向代理示例。
- Prometheus、Grafana 模板、JSON 日志。
- 自动备份、恢复演练和告警。
- 供应商签名目录更新。
- SBOM、许可证、依赖和 Secret 扫描。
- 50 并发流性能验收。
- 运维手册和客户交接。

## 后续版本

- `GatewayResponseCache` 的精确响应缓存实现，Coding Agent 默认关闭。
- OIDC/LDAP。
- Kubernetes Helm Chart 与多副本事件分发。
- SiliconFlow、StepFun、小米 MiMo 及更多聚合平台。
- 官方账单导入和自动差异报告。
- 更细粒度项目管理角色。
- 云 KMS。
- 可选 OpenTelemetry 全链路追踪。

## 明确不做

- 自动跨供应商路由与故障转移。
- 根据成本或质量自动选模型。
- Gateway 内跨协议转换。
- 供应商网页控制台模拟登录或 Cookie 抓取。
- 未经签名的远程可执行插件。

