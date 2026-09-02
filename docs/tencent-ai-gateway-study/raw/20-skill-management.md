# 135481 (doc 135481)


本文旨在引导您在 Skill 管理页对 Skill 进行创建、编辑、版本管理与生命周期治理。功能说明Skill 是 Agent 的原子能力单元，用于描述“这个 Agent 能做什么”。对外它对应 A2A 协议 AgentCard 中的 AgentSkill 对象（id/name/description/tags/examples），对内是 AI 网关侧一条可复用的能力配置记录。同一个 Skill 可被多个 Agent API 复用，修改一次即可同步生效。前提条件已创建 AI 网关实例，且实例状态为运行中。若需将 Skill 挂载到 Agent 并通过 A2A 协议对外暴露，网关数据面版本需 ≥ 3.9.5。若使用 ZIP 包方式创建 Skill，需提前准备包含 SKILL.md 的压缩包。Skill 管理创建 Skill1. 登录 AI 网关控制台。2. 在左侧导航栏选择实例列表，单击目标 AI 网关实例 ID 进入实例详情页。3. 单击左侧 Skill 管理后，单击新建 Skill。4. 在新建 Skill 弹窗中配置基础信息：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| Skill 名称 | 是 | 最长 64 个字符 | 展示名称，如“TAPD-Test”。 |
| 描述 | 是 | 最长 255 个字符 | 一句话描述能力，写入 AgentCard skills[].description，对外可见。 |
| Tags | 是 | 最多 5 个，单个最长 20 个字符 | 不可重复。写入 AgentCard skills[].tags。支持 Enter / 逗号 / 空格添加，Chip 形式展示，单击 × 删除。 |
| Examples | 否 | 最多 10 条，单条最长 512 个字符 | 使用示例，写入 AgentCard skills[].examples。单击添加示例新增一行。 |5. （可选）在 Skill 文件区域上传 ZIP 包：| 参数名 | 是否必填 | 取值范围 | 说明 |
| Skill 文件（.zip） | 否 | 仅支持 .zip 格式 | 支持拖拽或单击上传。上传后系统自动解析 SKILL.md 中的元信息（名称/描述/标签）并回填至上方字段，仅在字段为空时回填，不覆盖已填内容。 |6. 单击确定创建，列表中出现新建的 Skill。查看 Skill 详情1. 在 Skill 管理列表中单击目标 Skill 左侧的详情进入详情页。2. 详情页包含以下页签：| 页签 | 内容 |
| 基本信息 | Skill ID、名称、描述、Tags、Examples、当前版本号、状态、创建人、创建/更新时间 |

---

# 135481 (doc 135481)

本文旨在引导您在 Skill 管理页对 Skill 进行创建、编辑、版本管理与生命周期治理。功能说明Skill 是 Agent 的原子能力单元，用于描述“这个 Agent 能做什么”。对外它对应 A2A 协议 AgentCard 中的 AgentSkill 对象（id/name/description/tags/examples），对内是 AI 网关侧一条可复用的能力配置记录。同一个 Skill 可被多个 Agent API 复用，修改一次即可同步生效。前提条件已创建 AI 网关实例，且实例状态为运行中。若需将 Skill 挂载到 Agent 并通过 A2A 协议对外暴露，网关数据面版本需 ≥ 3.9.5。若使用 ZIP 包方式创建 Skill，需提前准备包含 SKILL.md 的压缩包。Skill 管理创建 Skill1. 登录 AI 网关控制台。2. 在左侧导航栏选择实例列表，单击目标 AI 网关实例 ID 进入实例详情页。3. 单击左侧 Skill 管理后，单击新建 Skill。4. 在新建 Skill 弹窗中配置基础信息：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| Skill 名称 | 是 | 最长 64 个字符 | 展示名称，如“TAPD-Test”。 |
| 描述 | 是 | 最长 255 个字符 | 一句话描述能力，写入 AgentCard skills[].description，对外可见。 |
| Tags | 是 | 最多 5 个，单个最长 20 个字符 | 不可重复。写入 AgentCard skills[].tags。支持 Enter / 逗号 / 空格添加，Chip 形式展示，单击 × 删除。 |
| Examples | 否 | 最多 10 条，单条最长 512 个字符 | 使用示例，写入 AgentCard skills[].examples。单击添加示例新增一行。 |5. （可选）在 Skill 文件区域上传 ZIP 包：| 参数名 | 是否必填 | 取值范围 | 说明 |
| Skill 文件（.zip） | 否 | 仅支持 .zip 格式 | 支持拖拽或单击上传。上传后系统自动解析 SKILL.md 中的元信息（名称/描述/标签）并回填至上方字段，仅在字段为空时回填，不覆盖已填内容。 |6. 单击确定创建，列表中出现新建的 Skill。查看 Skill 详情1. 在 Skill 管理列表中单击目标 Skill 左侧的详情进入详情页。2. 详情页包含以下页签：| 页签 | 内容 |
| 基本信息 | Skill ID、名称、描述、Tags、Examples、当前版本号、状态、创建人、创建/更新时间 |