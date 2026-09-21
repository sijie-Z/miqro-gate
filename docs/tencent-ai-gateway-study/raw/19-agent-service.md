# 135484 (doc 135484)


操作场景Agent 服务是 AI 网关中描述后端智能体（Agent）实例的资源对象，用于登记 Agent 的访问地址、协议能力与调用参数。Agent 服务是凭证与能力的承载容器，本身不对外暴露访问入口；对外入口由 Agent API 提供。您需要先创建 Agent 服务，再创建 Agent API 绑定该 Agent 服务，才能通过网关访问后端 Agent。AI 网关当前支持两类 Agent 供应商（Provider）：A2A：代理遵循 A2A（Agent-to-Agent）开放协议的智能体。网关会自动对外暴露 AgentCard 发现能力，并按你在 Agent 服务上声明的协议版本与传输绑定进行协议转换。Dify：代理 Dify 平台上托管的应用，支持 Chatbot、Agent、Workflow、Completion 四种应用类型。前提条件已创建 AI 网关实例，且实例状态为运行中。网关版本 ≥ 3.9.5。已获取后端 Agent 的可访问 URL（http/https）。创建 A2A 类型 Agent 服务前，若需声明技能（Skill），需已在 AI 能力市场 > Skill 管理中创建对应 Skill。创建 Dify 类型 Agent 服务前，已获取 Dify 应用的 API 密钥，或已在 密钥管理 中创建对应密钥。操作步骤创建 Agent 服务1. 登录 AI 网关控制台。2. 在左侧导航栏选择实例列表，单击目标 AI 网关实例 ID 进入实例详情页。3. 选择 Agent 管理 > Agent 服务页签，单击新建。4. 在新建 Agent 服务弹窗中，配置基础信息：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| 服务名称 | 必填 | 2-60个字符 | 同一网关实例内不可重复。创建后不可修改。 |
| 服务平台 | 必填 | A2A / Dify | 创建后不可修改。选择不同供应商，下方展示的协议参数不同。 |
| 超时时间 | 选填 | 1000~600000 毫秒，默认 60000 | 网关请求后端 Agent 的超时时间。 |
| 重试次数 | 选填 | 0~10，默认 5 | 网关请求后端 Agent 失败后的重试次数。 |
| 描述 | 选填 | 最长 200 个字符 | - |若供应商选择 A2A，继续配置 A2A 协议参数：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| 服务地址 | 必填 | 最长 512 个字符，需为合法 http/https 地址 | 后端 Agent 的访问地址，例如 https://a2a.example.com/v1。 |
| Agent 版本 | 必填 | 最长 32 个字符 | 当前 Agent 的业务版本号，会写入对外暴露的 AgentCard。 |
| 协议版本 | 必填 | 0.3 / 1.0 | 后端 Agent 所遵循的 A2A 协议版本。 |
| 协议绑定类型 | 必填 | JSONRPC / HTTP+JSON | A2A 协议的传输层绑定方式。JSONRPC 基于 HTTP POST 的 JSON-RPC 2.0，流式响应通过 SSE 返回；HTTP+JSON 为 RESTful 风格 HTTP API，流式响应同样通过 SSE 返回。 |
| 支持能力 | 选填 | 流式输出/推送通知 | 声明后端 Agent 支持的 A2A 协议能力，写入 AgentCard 的 capabilities 字段。 |
| 挂载 Skill | 选填 | 最多 50 个 | 从当前网关实例已创建的 Skill 中选择，作为该 Agent 对外声明的技能列表。所选 Skill 必须存在于当前网关实例，否则创建失败。 |若供应商选择 Dify，继续配置 Dify 参数：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| API 地址 | 必填 | 最长 512 个字符，需为合法 http/https 地址 | agent 访问地址，合法的 API 地址，需以 http:// 或 https:// 开头 |
| 应用类型 | 必填 | Chatbot / Agent / Workflow / Completion | 对应 Dify 平台的应用类型。创建后不可修改。 |
| API Key 来源 | 必填 | 引用密钥 / 手动输入 | 两者必须且只能选择其一。 |
| API Key | 凭证方式为“手动输入”时必填 | 手动输入，最长 512 个字符 | Dify 应用的 API 密钥。 |
| 密钥 | 凭证方式为“引用密钥”时必填 | 从密钥列表中选择 | 需为供应商为 Dify、资源类型为 Agent 的密钥。 |5. 单击确定，完成创建。列表中出现新建的 Agent 服务，可查看其 ID、供应商、URL 与关联的 Agent API 数量。查看 Agent 服务详情1. 在 Agent 服务列表中，单击目标 Agent 服务名称进入详情页。2. 详情页展示基础信息与协议信息：基础信息：Agent 服务 ID、名称、供应商、URL、描述、超时时间、重试次数、创建/修改时间。服务信息：服务类型、服务协议、服务地址/端口、服务路径。编辑 Agent 服务1. 在 Agent 服务列表中，找到目标 Agent 服务，单击操作列的编辑。2. 修改所需参数后单击确定。说明：服务名称、服务平台、应用类型（如选择 Dify）不支持修改。如需变更，请删除后重新创建。该 Agent 服务被任意 Agent API 绑定时，不允许修改。 请先解除 Agent API 的绑定关系（或删除对应 Agent API）后再修改。删除 Agent 服务1. 在 Agent 服务列表中，找到目标 Agent 服务，单击操作列的删除。2. 在确认弹窗中单击确定。说明：该 Agent 服务被任意 Agent API 绑定时，不允许删除。 系统会提示关联的资源列表，请先删除或解绑对应的 Agent API。Dify 供应商的 Agent 服务被删除时，其关联的密钥不会被同步删除，如不再需要请在密钥管理中手动清理。相关说明A2A 类型 Agent 服务与 Agent API 的协作关系Agent 服务与 Agent API 必须协议一致才能绑定：| 层级 | 资源 | 关键配置 |
| 对外入口 | Agent API（Protocol = A2A） | BasePath、Skill 启用状态、绑定的 Agent 服务 |
| 后端登记 | Agent 服务（Provider = A2A） | URL、协议版本、传输绑定、能力声明、关联 Skill、超时与重试 |关键约束：协议为 A2A 的 Agent API 只能绑定供应商为 A2A 的 Agent 服务，反之亦然。跨供应商绑定会被拒绝。Agent API 绑定 Agent 服务后，不支持更换所绑定的 Agent 服务。Agent API 创建时会从所绑定 Agent 服务的 Skill 列表中取快照，后续在 Agent API 上仅支持切换单个 Skill 的启用/停用状态，不支持新增或移除 Skill。如需调整 Skill 范围，需在 Agent 服务上修改关联 Skill。协议为 A2A 的 Agent API 不支持自定义路由的增删改。其访问入口由系统按 BasePath 自动创建并维护。若需要自定义路由能力，请使用 Custom 协议的 Agent API。A2A 协议访问方式Agent API 创建完成后，调用方可按 A2A 标准流程访问：1. 调用方向网关的 AgentCard 发现端点发起请求，获取该 Agent 的 AgentCard（包含名称、描述、技能列表、支持的传输协议、安全要求）。2. 调用方根据 AgentCard 中声明的传输协议与你在 Agent 服务上配置的传输绑定建立连接。3. 调用方发送 A2A 消息，网关按 Agent 服务配置的 URL、超时时间、重试次数转发到后端 Agent。A2A 协议的任务生命周期由后端 Agent 负责维护，网关不改变任务状态语义。配额与限制同一网关实例内 Agent 服务名称唯一。单个 A2A 类型 Agent 服务最多关联 50 个 Skill。Agent 服务的超时时间上限为 600000 毫秒（10 分钟），重试次数上限为 10 次。所有 Agent 服务能力要求网关版本 ≥ 3.9.5。

---

# 135484 (doc 135484)

操作场景Agent 服务是 AI 网关中描述后端智能体（Agent）实例的资源对象，用于登记 Agent 的访问地址、协议能力与调用参数。Agent 服务是凭证与能力的承载容器，本身不对外暴露访问入口；对外入口由 Agent API 提供。您需要先创建 Agent 服务，再创建 Agent API 绑定该 Agent 服务，才能通过网关访问后端 Agent。AI 网关当前支持两类 Agent 供应商（Provider）：A2A：代理遵循 A2A（Agent-to-Agent）开放协议的智能体。网关会自动对外暴露 AgentCard 发现能力，并按你在 Agent 服务上声明的协议版本与传输绑定进行协议转换。Dify：代理 Dify 平台上托管的应用，支持 Chatbot、Agent、Workflow、Completion 四种应用类型。前提条件已创建 AI 网关实例，且实例状态为运行中。网关版本 ≥ 3.9.5。已获取后端 Agent 的可访问 URL（http/https）。创建 A2A 类型 Agent 服务前，若需声明技能（Skill），需已在 AI 能力市场 > Skill 管理中创建对应 Skill。创建 Dify 类型 Agent 服务前，已获取 Dify 应用的 API 密钥，或已在 密钥管理 中创建对应密钥。操作步骤创建 Agent 服务1. 登录 AI 网关控制台。2. 在左侧导航栏选择实例列表，单击目标 AI 网关实例 ID 进入实例详情页。3. 选择 Agent 管理 > Agent 服务页签，单击新建。4. 在新建 Agent 服务弹窗中，配置基础信息：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| 服务名称 | 必填 | 2-60个字符 | 同一网关实例内不可重复。创建后不可修改。 |
| 服务平台 | 必填 | A2A / Dify | 创建后不可修改。选择不同供应商，下方展示的协议参数不同。 |
| 超时时间 | 选填 | 1000~600000 毫秒，默认 60000 | 网关请求后端 Agent 的超时时间。 |
| 重试次数 | 选填 | 0~10，默认 5 | 网关请求后端 Agent 失败后的重试次数。 |
| 描述 | 选填 | 最长 200 个字符 | - |若供应商选择 A2A，继续配置 A2A 协议参数：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| 服务地址 | 必填 | 最长 512 个字符，需为合法 http/https 地址 | 后端 Agent 的访问地址，例如 https://a2a.example.com/v1。 |
| Agent 版本 | 必填 | 最长 32 个字符 | 当前 Agent 的业务版本号，会写入对外暴露的 AgentCard。 |
| 协议版本 | 必填 | 0.3 / 1.0 | 后端 Agent 所遵循的 A2A 协议版本。 |
| 协议绑定类型 | 必填 | JSONRPC / HTTP+JSON | A2A 协议的传输层绑定方式。JSONRPC 基于 HTTP POST 的 JSON-RPC 2.0，流式响应通过 SSE 返回；HTTP+JSON 为 RESTful 风格 HTTP API，流式响应同样通过 SSE 返回。 |
| 支持能力 | 选填 | 流式输出/推送通知 | 声明后端 Agent 支持的 A2A 协议能力，写入 AgentCard 的 capabilities 字段。 |
| 挂载 Skill | 选填 | 最多 50 个 | 从当前网关实例已创建的 Skill 中选择，作为该 Agent 对外声明的技能列表。所选 Skill 必须存在于当前网关实例，否则创建失败。 |若供应商选择 Dify，继续配置 Dify 参数：| 参数名 | 是否必填 | 取值范围/默认值 | 说明 |
| API 地址 | 必填 | 最长 512 个字符，需为合法 http/https 地址 | agent 访问地址，合法的 API 地址，需以 http:// 或 https:// 开头 |
| 应用类型 | 必填 | Chatbot / Agent / Workflow / Completion | 对应 Dify 平台的应用类型。创建后不可修改。 |
| API Key 来源 | 必填 | 引用密钥 / 手动输入 | 两者必须且只能选择其一。 |
| API Key | 凭证方式为“手动输入”时必填 | 手动输入，最长 512 个字符 | Dify 应用的 API 密钥。 |
| 密钥 | 凭证方式为“引用密钥”时必填 | 从密钥列表中选择 | 需为供应商为 Dify、资源类型为 Agent 的密钥。 |5. 单击确定，完成创建。列表中出现新建的 Agent 服务，可查看其 ID、供应商、URL 与关联的 Agent API 数量。查看 Agent 服务详情1. 在 Agent 服务列表中，单击目标 Agent 服务名称进入详情页。2. 详情页展示基础信息与协议信息：基础信息：Agent 服务 ID、名称、供应商、URL、描述、超时时间、重试次数、创建/修改时间。服务信息：服务类型、服务协议、服务地址/端口、服务路径。编辑 Agent 服务1. 在 Agent 服务列表中，找到目标 Agent 服务，单击操作列的编辑。2. 修改所需参数后单击确定。说明：服务名称、服务平台、应用类型（如选择 Dify）不支持修改。如需变更，请删除后重新创建。该 Agent 服务被任意 Agent API 绑定时，不允许修改。 请先解除 Agent API 的绑定关系（或删除对应 Agent API）后再修改。删除 Agent 服务1. 在 Agent 服务列表中，找到目标 Agent 服务，单击操作列的删除。2. 在确认弹窗中单击确定。说明：该 Agent 服务被任意 Agent API 绑定时，不允许删除。 系统会提示关联的资源列表，请先删除或解绑对应的 Agent API。Dify 供应商的 Agent 服务被删除时，其关联的密钥不会被同步删除，如不再需要请在密钥管理中手动清理。相关说明A2A 类型 Agent 服务与 Agent API 的协作关系Agent 服务与 Agent API 必须协议一致才能绑定：| 层级 | 资源 | 关键配置 |
| 对外入口 | Agent API（Protocol = A2A） | BasePath、Skill 启用状态、绑定的 Agent 服务 |
| 后端登记 | Agent 服务（Provider = A2A） | URL、协议版本、传输绑定、能力声明、关联 Skill、超时与重试 |关键约束：协议为 A2A 的 Agent API 只能绑定供应商为 A2A 的 Agent 服务，反之亦然。跨供应商绑定会被拒绝。Agent API 绑定 Agent 服务后，不支持更换所绑定的 Agent 服务。Agent API 创建时会从所绑定 Agent 服务的 Skill 列表中取快照，后续在 Agent API 上仅支持切换单个 Skill 的启用/停用状态，不支持新增或移除 Skill。如需调整 Skill 范围，需在 Agent 服务上修改关联 Skill。协议为 A2A 的 Agent API 不支持自定义路由的增删改。其访问入口由系统按 BasePath 自动创建并维护。若需要自定义路由能力，请使用 Custom 协议的 Agent API。A2A 协议访问方式Agent API 创建完成后，调用方可按 A2A 标准流程访问：1. 调用方向网关的 AgentCard 发现端点发起请求，获取该 Agent 的 AgentCard（包含名称、描述、技能列表、支持的传输协议、安全要求）。2. 调用方根据 AgentCard 中声明的传输协议与你在 Agent 服务上配置的传输绑定建立连接。3. 调用方发送 A2A 消息，网关按 Agent 服务配置的 URL、超时时间、重试次数转发到后端 Agent。A2A 协议的任务生命周期由后端 Agent 负责维护，网关不改变任务状态语义。配额与限制同一网关实例内 Agent 服务名称唯一。单个 A2A 类型 Agent 服务最多关联 50 个 Skill。Agent 服务的超时时间上限为 600000 毫秒（10 分钟），重试次数上限为 10 次。所有 Agent 服务能力要求网关版本 ≥ 3.9.5。