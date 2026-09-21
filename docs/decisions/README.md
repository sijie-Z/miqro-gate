# 架构决策记录

状态口径（每篇首行给出，本文件必须与之一致）：

- **Accepted**——已拍板、按文执行；括号里可注明拍板日期，或「部分采纳」的边界。
- **Proposed（待所有者拍板）**——只留选项与代价，**拍板前不实现**；否决同样是有效产出（issue 关闭为「已决策」）。
- **Superseded**——已被后一篇采纳或取代，正文保留为提案原文。

> **「还有哪些要拍板」直接看下面的「待拍板」一节。** 这份索引曾漂移过三次：ADR-0012/0013 早已 Accepted 却仍列作草案（近两周）、ADR-0024 转「部分 Accepted」后索引未跟（#1018）、ADR-0009–0011 从未入索引。因此加了 CI 闸门 `ADR index`（`deploy/docs/check-adr-index.py`）：索引行与文件首行不一致、分组放错、或漏掉任何一篇，都会红。状态写在文件里、索引复述一遍，两者由机器比对——人只需要改一处。

## 已拍板

- [ADR-0001：Java 与 Vue 技术栈（Accepted）](0001-java-vue-stack.md)
- [ADR-0002：Gateway 采用协议透明代理（Accepted）](0002-transparent-proxy.md)
- [ADR-0003：第一版不做模型响应缓存（Accepted）](0003-no-response-cache.md)
- [ADR-0004：不采用 LiteLLM 运行时（Accepted）](0004-no-litellm-runtime.md)
- [ADR-0005：第一版不引入 Redis（Accepted）](0005-no-redis-v1.md)
- [ADR-0006：Gateway WebFlux，Control Plane MVC（Accepted）](0006-split-webflux-mvc.md)
- [ADR-0007：产品与工程标识采用 MiQroKey（Accepted）](0007-miqrokey-product-identity.md)
- [ADR-0009：启用响应缓存（对齐腾讯云 AI 网关 L1 精确缓存方案）（Accepted）](0009-enable-response-cache.md)
- [ADR-0010：外部系统 API 认证通道（消费者 API Key）与计费查询 API（Accepted）](0010-consumer-api-key-billing.md)
- [ADR-0011：消费者 JWT 认证（外部系统自签 Token，网关公钥验签）（Accepted）](0011-consumer-jwt-authentication.md)
- [ADR-0012：Kafka 引入评估（Accepted，2026-09-05）](0012-kafka-events-proposal.md)
- [ADR-0013：MCP 调用代理接线（Accepted，2026-09-05）](0013-mcp-proxy-wiring-proposal.md)
- [ADR-0014：请求内容合规留痕管道 + Kafka 事件流 + OAuth 用户映射（Accepted，2026-09-05）](0014-content-retention-and-kafka-events.md)
- [ADR-0015：开放管理 API 机器凭证（Accepted，2026-09-07）](0015-open-admin-api-machine-credentials.md)
- [ADR-0016：开放管理 API 机器执行器（Accepted，2026-09-08，A+C）](0016-open-admin-api-machine-executor.md)
- [ADR-0017：平台 OIDC 登录（P0a）（Accepted）](0017-platform-oidc-login.md)
- [ADR-0018：单密钥多项目——key×project 多绑定与标签选择（Accepted，方向，2026-09-15）](0018-single-key-multi-project.md)
- [ADR-0020：配额软着陆——REJECT 规则超限拒绝请求（429）（Accepted，2026-09-16）](0020-quota-soft-landing.md)
- [ADR-0022：语义缓存启用评估——正文向量化出网关的合规边界（Accepted：维持不启用 + 批准 P0/P1 测量，P2 若做则 B2 优先）](0022-semantic-cache-evaluation.md)
- [ADR-0024：请求侧可选改造②——错误驱动的整流重试（Accepted（部分）：选项 B 观察档已作为一期交付（#770 / PR #1018）；C/D/E 仍为提案，见文件 §7）](0024-request-side-rectification-retry.md)
- [ADR-0025：Agent 生命周期补齐——删除 / 重新启用 / 改名（Accepted：选项 D，实现见 #1012）](0025-agent-lifecycle.md)
- [ADR-0027：内容过滤 Phase 2——本地规则 + shadow + 异步 + 不阻断（Accepted，2026-09-20；#740 保持 OPEN 跟踪实现）](0027-content-filtering-phase2.md)

## 已被取代

- [ADR-0019：配额硬阻断（超限拒绝）— 草案（Superseded：由 ADR-0020 采纳并落地，正文保留为提案原文）](0019-quota-hard-block-proposal.md)

## 待拍板（等一句话）

- [ADR-0021：同产品凭证回退——多凭证切换与「每笔唯一归属」的兼容设计（Proposed，待所有者拍板）](0021-same-product-credential-failover.md)
- [ADR-0023：请求侧可选改造①——prompt 缓存断点自动注入（Proposed，待所有者拍板；opt-in 按 Key，见 issue #769）](0023-request-side-cache-breakpoint-injection.md)
- [ADR-0026：速率限流（TPM/RPM）评估与决策留档（Proposed，待所有者拍板；选项 D「只告警」已作为观测一期交付：issue #706 / PR #1016，A/B/C 未动）](0026-rate-limiting-evaluation.md)

- （非独立提案）ADR-0024 的二期选项 C/D/E：正文已「部分 Accepted」，二期未拍板——按文件 §3 的触发条件、等一期观察数据支持。
