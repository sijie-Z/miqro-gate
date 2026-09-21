# 135906 (doc 135906)


场景概述本文以“标准 MCP 服务”为例：已有一个公网可访问的 MCP 后端（本文以 uapis.cn 为例，含 109 个免费工具），您通过 AI 网关将其代理暴露给内部 Agent 调用。网关负责鉴权、流量管控，后端工具透明透传。为什么需要一个可调通的后端？ 本文所有后端地址均为真实可用的公共服务，每一步都可以用 curl 验证。前提条件1. 已创建 AI 网关实例，实例状态为运行中。具体操作请参见 新建 AI 网关。2. 网关可与公网通信（本文使用公网 MCP 后端服务）。操作步骤步骤一：创建 MCP 服务1. 登录 AI 网关控制台，选择目标 AI 网关实例进入详情。2. 在左侧导航栏，选择 MCP 管理。3. 在“MCP 服务”页面，单击新建。4. 参考下表配置：4.1 基本信息| 参数 | 填写说明 |
| MCP 服务名称 | uapis-tools |
| 服务展示名称 | UAPI 公共工具集 |
| 服务类型 | 标准 MCP 服务 |
| 请求协议 | Streamable HTTP（默认） |
| 描述 | 公共 MCP 工具集（含 109 个工具：JSON 格式化、时间戳转换、每日单词、IP 查询等） |4.2 后端服务配置| 参数 | 填写说明 |
| 后端类型 | 域名/IP |
| 服务协议 | HTTPS |
| 服务地址 | uapis.cn |
| 服务端口 | 443 |
| 服务路径 | /mcp |
| 超时时间 | 60000 毫秒（默认） |
| 重试次数 | 5（默认） |4.3 会话管理：默认即可。完成 MCP 服务创建后单击进入服务详情页面，选择“Tools 管理”页签，即可见自动同步的 Tools 列表步骤二：后端鉴权配置（二选一）：| 模式 | 适用场景 | 后端鉴权配置 |
| Visitor 模式（快速体验，可直接跳过步骤二） | 临时演示、快速验证 | 不填任何鉴权信息，网关侧消费者 Key 鉴权即可，不向下透传 |
| API Key 模式（验证完整鉴权链路） | 需要验证后端 Key > 网关 > 消费者三级鉴权 | 添加请求头 Authorization: Bearer  |1. 创建“消费者密钥”：在目标 AI 网关实例详情页中，菜单栏左侧选择密钥管理 > 消费者密钥 > 新建消费者密钥。| 参数 | 填写说明 |
| 密钥名称 | test |
| 密钥凭证类型 | API Key |
| 生成方式 | 自定义 |
| 凭证内容 | sk-…REDACTED（仅作为测试使用） |2. 创建“消费者组”：在目标 AI 网关实例详情页中，菜单栏左侧选择消费者管理 > 消费者组管理 > 新建名为 test-group 的消费者组。3. 创建“消费者”：在目标 AI 网关实例详情页中，菜单栏左侧选择消费者管理 > 消费者管理 > 新建名为 test 的消费者。| 参数 | 填写说明 |
| 消费者名称 | test |
| 所属消费者组 | test-group |
| 选择密钥 | test |4. 配置鉴权：单击刚才创建的 MCP 服务进入服务详情页，单击“访问控制”页签。认证配置：启用 API Key 认证。Server 访问控制：配置“白名单”访问模式，消费者组选择 test-group，消费者选择 test。步骤三：获取接入地址并发起调用1. 获取接入地址在 MCP 服务列表页面，单击 uapis-tools 进入详情页，调用方式区域会显示接入地址，格式如下：  http://<网关IP>/mcpservers/uapis-tools/mcp说明：需要公网访问时，请在实例基础信息 > 网络配置中开启公网负载均衡。说明：访问前先确认后端 MCP 端点本身是可用的，可以直接对 uapis.cn 发起调用：这两条命令不需要网关、不需要 API Key、不需要注册，5 秒内即可验证 MCP JSON-RPC 2.0 协议的完整调用链路。# 获取工具列表curl -s -X POST https://uapis.cn/mcp \\  -H "Content-Type: application/json" \\  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' \\  | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'工具数: {len(d[\\"result\\"][\\"tools\\"])}')"# 预期输出: 工具数2. 验证 tools/listcurl -X POST http://<网关IP>/mcpservers/uapis-tools/mcp \\  -H "Content-Type: application/json" \\  -H "Authorization: Bearer <API_KEY>" \\  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'预期响应（约 109 个工具）：{  "jsonrpc": "2.0",  "id": 1,  "result": {    "tools": [      {"name": "post_convert_json", "description": "JSON格式化工具..."},      {"name": "get_convert_unixtime", "description": "Unix时间戳转换..."},      {"name": "get_daily_news_image", "description": "每日新闻图片..."},      {"name": "get_daily_word", "description": "每日英语单词..."},      {"name": "get_dictionary_lookup", "description": "词典查询..."}      ...    ]  }}3. 验证 tools/call  curl -X POST http://<网关IP>/mcpservers/uapis-tools/mcp \\  -H "Content-Type: application/json" \\  -H "Authorization: Bearer <API_KEY>" \\  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_daily_word","arguments":{}},"id":2}'预期响应（返回真实数据）：{  "jsonrpc": "2.0",  "id": 2,  "result": {    "content": [{      "type": "text",      "text": "{\\"date\\":\\"2026-08-10\\",\\"words\\":[{\\"word\\":\\"initiative\\",\\"translation\\":\\"n. 主动行动, 首创精神, 主动权\\",\\"definition\\":\\"n readiness to embark on bold new ventures\\",\\"examples\\":[{\\"text\\":\\"Local businesses are teaming up for a charity initiative.\\",\\"translation\\":\\"当地企业正在联手举办一项慈善活动。\\"}]}]}"    }]  }}

---

# 135906 (doc 135906)

场景概述本文以“标准 MCP 服务”为例：已有一个公网可访问的 MCP 后端（本文以 uapis.cn 为例，含 109 个免费工具），您通过 AI 网关将其代理暴露给内部 Agent 调用。网关负责鉴权、流量管控，后端工具透明透传。为什么需要一个可调通的后端？ 本文所有后端地址均为真实可用的公共服务，每一步都可以用 curl 验证。前提条件1. 已创建 AI 网关实例，实例状态为运行中。具体操作请参见 新建 AI 网关。2. 网关可与公网通信（本文使用公网 MCP 后端服务）。操作步骤步骤一：创建 MCP 服务1. 登录 AI 网关控制台，选择目标 AI 网关实例进入详情。2. 在左侧导航栏，选择 MCP 管理。3. 在“MCP 服务”页面，单击新建。4. 参考下表配置：4.1 基本信息| 参数 | 填写说明 |
| MCP 服务名称 | uapis-tools |
| 服务展示名称 | UAPI 公共工具集 |
| 服务类型 | 标准 MCP 服务 |
| 请求协议 | Streamable HTTP（默认） |
| 描述 | 公共 MCP 工具集（含 109 个工具：JSON 格式化、时间戳转换、每日单词、IP 查询等） |4.2 后端服务配置| 参数 | 填写说明 |
| 后端类型 | 域名/IP |
| 服务协议 | HTTPS |
| 服务地址 | uapis.cn |
| 服务端口 | 443 |
| 服务路径 | /mcp |
| 超时时间 | 60000 毫秒（默认） |
| 重试次数 | 5（默认） |4.3 会话管理：默认即可。完成 MCP 服务创建后单击进入服务详情页面，选择“Tools 管理”页签，即可见自动同步的 Tools 列表步骤二：后端鉴权配置（二选一）：| 模式 | 适用场景 | 后端鉴权配置 |
| Visitor 模式（快速体验，可直接跳过步骤二） | 临时演示、快速验证 | 不填任何鉴权信息，网关侧消费者 Key 鉴权即可，不向下透传 |
| API Key 模式（验证完整鉴权链路） | 需要验证后端 Key > 网关 > 消费者三级鉴权 | 添加请求头 Authorization: Bearer  |1. 创建“消费者密钥”：在目标 AI 网关实例详情页中，菜单栏左侧选择密钥管理 > 消费者密钥 > 新建消费者密钥。| 参数 | 填写说明 |
| 密钥名称 | test |
| 密钥凭证类型 | API Key |
| 生成方式 | 自定义 |
| 凭证内容 | sk-…REDACTED（仅作为测试使用） |2. 创建“消费者组”：在目标 AI 网关实例详情页中，菜单栏左侧选择消费者管理 > 消费者组管理 > 新建名为 test-group 的消费者组。3. 创建“消费者”：在目标 AI 网关实例详情页中，菜单栏左侧选择消费者管理 > 消费者管理 > 新建名为 test 的消费者。| 参数 | 填写说明 |
| 消费者名称 | test |
| 所属消费者组 | test-group |
| 选择密钥 | test |4. 配置鉴权：单击刚才创建的 MCP 服务进入服务详情页，单击“访问控制”页签。认证配置：启用 API Key 认证。Server 访问控制：配置“白名单”访问模式，消费者组选择 test-group，消费者选择 test。步骤三：获取接入地址并发起调用1. 获取接入地址在 MCP 服务列表页面，单击 uapis-tools 进入详情页，调用方式区域会显示接入地址，格式如下：  http:///mcpservers/uapis-tools/mcp说明：需要公网访问时，请在实例基础信息 > 网络配置中开启公网负载均衡。说明：访问前先确认后端 MCP 端点本身是可用的，可以直接对 uapis.cn 发起调用：这两条命令不需要网关、不需要 API Key、不需要注册，5 秒内即可验证 MCP JSON-RPC 2.0 协议的完整调用链路。# 获取工具列表curl -s -X POST https://uapis.cn/mcp \\  -H "Content-Type: application/json" \\  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}' \\  | python3 -c "import sys,json;d=json.load(sys.stdin);print(f'工具数: {len(d[\\"result\\"][\\"tools\\"])}')"# 预期输出: 工具数2. 验证 tools/listcurl -X POST http:///mcpservers/uapis-tools/mcp \\  -H "Content-Type: application/json" \\  -H "Authorization: Bearer " \\  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'预期响应（约 109 个工具）：{  "jsonrpc": "2.0",  "id": 1,  "result": {    "tools": [      {"name": "post_convert_json", "description": "JSON格式化工具..."},      {"name": "get_convert_unixtime", "description": "Unix时间戳转换..."},      {"name": "get_daily_news_image", "description": "每日新闻图片..."},      {"name": "get_daily_word", "description": "每日英语单词..."},      {"name": "get_dictionary_lookup", "description": "词典查询..."}      ...    ]  }}3. 验证 tools/call  curl -X POST http:///mcpservers/uapis-tools/mcp \\  -H "Content-Type: application/json" \\  -H "Authorization: Bearer " \\  -d '{"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_daily_word","arguments":{}},"id":2}'预期响应（返回真实数据）：{  "jsonrpc": "2.0",  "id": 2,  "result": {    "content": [{      "type": "text",      "text": "{\\"date\\":\\"2026-08-10\\",\\"words\\":[{\\"word\\":\\"initiative\\",\\"translation\\":\\"n. 主动行动, 首创精神, 主动权\\",\\"definition\\":\\"n readiness to embark on bold new ventures\\",\\"examples\\":[{\\"text\\":\\"Local businesses are teaming up for a charity initiative.\\",\\"translation\\":\\"当地企业正在联手举办一项慈善活动。\\"}]}]}"    }]  }}