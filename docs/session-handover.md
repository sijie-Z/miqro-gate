# Session Handover（新会话续接包 · 2026-09-03）

> 生成原因：前一工作会话上下文接近上限，按项目协议（CLAUDE.md Compact Instructions / claude-code-context-strategy.md）落盘续接。**新会话直接复制下方「启动提示词」全文到 Claude Code 即可**；本文件是同一内容的仓库内副本（随 #122 分支合入 develop）。

## 启动提示词（复制这段到新会话）

```text
继续 MiQroGate（sijie-Z/miqro-gate，仓库在 D:\Desktop\My_projects\MiQro-key\miqro-key-gateway）开发。严格按 gitflow（docs/git-workflow.md）执行。

## 必读文件（按序）
1. CLAUDE.md（§1-§9：执行协议/锁定决策/验证命令/禁止事项）
2. docs/claude-code-execution-contract.md
3. docs/progress.md（顶部 Current State 为最新；2026-09-02 起多 Goal 完成记录在案）
4. docs/git-workflow.md
5. docs/feature-backlog.md（★★ 功能总登记表：58 项 PLANNED/SCAFFOLD/BLOCKED/ADR/DECLINED + 出处与前置——后续所有立项先查此表，实施后更新状态）
6. docs/tencent-ai-gateway-study/README.md（功能参考腾讯的素材库；阿里对照在 docs/ai-gateway-comparison.md 与 platform-middleware-roadmap.md）

## 当前状态（2026-09-03）
- 已交付链：#117 G6.5 发布收尾 → #118 模型审批流 → #119 配额规则 → #120 配额告警 → #121 缓存 ROI → #122 MCP 两级访问控制 + feature-backlog（#122 分支 goal/mcp-access-control 已 push，等 CI 全绿后按授权模式合并：gh pr merge --squash --delete-branch）
- 后端基线 ~2156 tests / 前端 vitest 94 / e2e 35；23 产品适配器全 IMPLEMENTED（真实凭证 WAITING_FOR_CREDENTIAL）
- 用户指令（长期有效）：「所有关于文档的功能我们都做；不清晰的先留想法和架子（feature-backlog 的 SCAFFOLD 就是架子），内容以后再说」——按 backlog 从 PLANNED 组开始连续做，做完一轮回写状态再下一项，不必每项回头问。

## 推荐实施顺序（feature-backlog PLANNED 组）
1. F24 默认配额模板（腾讯 A10：全局模板 + 创建时快照复制；手动规则覆盖默认）——配额线延伸，语义完整，无外部依赖 → 开 goal/ 分支做
2. F03 模型审批 Webhook 通知 + F04 用户自助配额可见性 + F06 导出/删除定时 GC（三个小闭环可各一个 goal）
3. F02 缓存键升级对齐腾讯「最后一条 user 消息」+ F35 usage 队列饱和同步写入应急模式
4. F01 MCP 调用代理接线（判定策略 McpAccessPolicy 已就绪，需 SSE/Streamable HTTP 传输实现）→ F11-F15 MCP 路由规则/重试/熔断/分组/元数据日志
5. F19/F20 对账与 adjustment（SCAFFOLD：先定导入器契约，供应商解析器待真实账单样本）
BLOCKED/ADR 组不动（等 leader/ADR）；红线组（DECLINED）绝不碰。

## 环境与操作坑（重要）
- Windows Git Bash；每条 Maven 命令前：export JAVA_HOME="D:\programming\jdk-21.0.12.1+1" && export PATH="$JAVA_HOME/bin:$PATH"；仓库根跑 ./mvnw.cmd -f backend/pom.xml ...
- GitHub 直连不稳：push/gh 前加一次性 HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897
- shell cwd 会漂移（cd 只对当条命令/前台生效；后台任务不改前台 cwd）；报 "not a git repository" 先 cd 仓库根
- vitest 必须在 frontend 目录跑（@ 别名只在 frontend）；npm run lint（--fix）会把 CRLF 重写为 LF 造成 EOL-only M——跑后用 comm 技巧 git restore（git diff 空即 EOL-only）
- 集成测试用 -P integration + -Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false
- 已知 flaky（重跑即过，勿当回归）：HmacVirtualKeyProviderTest.shouldFollowFormat（随机边界）、AuditChainIntegrityTest.concurrentWritersFromEmptyTable/preLockTimestampsDoNotAffectHeadOrdering（并发时序）
- 文本块 SQL 尾随空格会被 Java trim 掉（RETURNING 拼接要换行）；PG 对 "? IS NULL OR x = ?" 需显式 ::cast
- 前端交互测试：TPopup stub 模式（见既有 spec）；t-dialog 内 wrapper.find 可用（Tools 弹窗先例）
- 图标导出名以 tdesign-icons-vue-next/esm/icons.d.ts 为准（无 EditPenIcon 之类）

## 待用户处理事项（遇到就报告，不阻塞）
- 各 PR 合并授权（模式已确立：CI 全绿 → squash merge → 删分支）
- CodeRabbit OSS 首次 review 需所有者在 coderabbit.ai 批准
- 版本 tag（0.1.0-SNAPSHOT）与正式发布授权
- leader/平台 BLOCKED 项（用户同步/OAuth 形态/Kafka 场景/服务接线/热更新/SkillHub 来源/账单样本）

## 财务/边界红线（禁碰）
不限流不因预算阻断（硬阻断需 ADR）、不跨供应商路由/不故障切换、透明代理不读/不改写正文、不存正文、Virtual Key 1:1、凭证 AES-GCM/Key HMAC 摘要、目录签名。
```

## 仓库内快速索引

- 最新状态：docs/progress.md 顶部 Current State
- 功能全景与状态机：docs/feature-backlog.md（实施后更新对应行状态 + 在 progress.md 记录 Goal）
- 文档一致性缺口（D01-D04）：feature-backlog 末尾——ADR-0008 缺失文件、future-kafka 悬空引用、429 信号量出处待核、生产缓存表述复核
- 交接要点（环境/CI/机器人）：progress.md「2026-09-02 会话交接要点」段
