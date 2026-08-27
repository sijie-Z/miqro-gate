# ADR-0007：产品与工程标识采用 MiQroKey

- Status: Accepted
- Date: 2026-07-17

## Context

原工作名称只是功能性占位描述，GitHub 临时仓库名称还存在拼写错误。项目即将交给 Claude Code 初始化工程，此时是统一品牌、包名、配置和运行标识的最后低成本窗口。

客户公司为 MiQroEra。产品需要体现 Virtual Key 管理和透明 Gateway，但不能暗示智能模型路由或公共聚合服务。

## Decision

统一采用：

| 类别 | 标识 |
|---|---|
| 产品名 | `MiQroKey` |
| 正式说明名 | `MiQroKey Gateway` |
| GitHub 仓库 | `miqro-key-gateway` |
| Java group/package | `com.miqroera.miqrokey` |
| Maven artifact 前缀 | `miqrokey-` |
| Docker Compose project/镜像前缀 | `miqrokey` |
| PostgreSQL database/user | `miqrokey` |
| 配置环境变量前缀 | `MIQROKEY_` |
| Virtual Key 可见前缀 | `mqk_live_` |
| HTTP 内部 Header 前缀 | `X-MiQroKey-` |
| 指标前缀 | `miqrokey_` |
| Portal 标题 | `MiQroKey` |

目录名是否立即从本机现有路径改名不影响 Git 内容；G0.1 创建的仓库根结构和所有生成物使用新标识。

## Consequences

- 文档、配置示例、包名和测试 fixtures 不再使用旧的临时缩写。
- 原拼写错误的空仓库已删除，新空仓库 `miqro-key-gateway` 已创建；G0.1 使用新地址。
- Claude Code 不得自行创造 `MiQroAI`、`KeyHub` 等别名，也不得恢复临时名称。
- 未来若因客户品牌规范变更产品名，需要新的 ADR 和一次受控 migration。
