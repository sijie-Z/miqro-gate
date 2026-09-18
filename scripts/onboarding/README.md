# 接入器（参考实现）

来源：issue #742 第二块（slice 1 是 `docs/client-onboarding.md` 的指南矩阵）。
本目录把「怎么接进门」做成可执行的注入工具：**打印**各客户端的可复制配置、**写入**配置文件形式
（幂等 + 时间戳备份 + 托管块）、**验证**凭据真的被数据面接受。

边界（明确不做）：

- **不做网络层劫持**（hosts/本地代理/自签证书）——红线，见指南 §1；
- **配置器形态（CC Switch）不在本工具范围**：控制台 Key 页「接入 CC Switch」深链
  （`ccswitch://v1/import?…`）是既有实现，直接用它；
- Claude Code 的 `settings.json` 是 JSON 合并，依赖 `jq`（无 jq 时拒写并提示改用 `print` 粘贴）。

## 用法

```bash
S=scripts/onboarding/miqro-onboard.sh
GW=https://gateway.example.com

# 打印（不落盘）
sh $S print env            --gateway $GW --key mqk_live_…        # Claude Code 三平台：--shell posix|cmd|powershell
sh $S print claude-settings --gateway $GW --key mqk_live_…       # VSCode/JetBrains 插件用 settings.json 片段
sh $S print codex          --gateway $GW --key mqk_live_… --model deepseek-flash
sh $S print openai         --gateway $GW --key mqk_live_… --model deepseek-flash
sh $S print curl           --gateway $GW --key mqk_live_… --model deepseek-flash
sh $S print mcp            --gateway $GW --key mqk_api_… --mcp-url https://gateway.example.com/mcpservers/<服务>/mcp

# 写入（幂等；改动前备份 <file>.bak-<UTC 时间戳>；--dry-run 只看不写）
sh $S apply env             --gateway $GW --key mqk_live_… --file ./miqro-env.sh --flavor anthropic
sh $S apply dotenv          --gateway $GW --key mqk_live_… --file ./.env --flavor openai
sh $S apply claude-settings --gateway $GW --key mqk_live_… --file ~/.claude/settings.json

# 验证（GET <gateway>/v1/models，按状态码归因）
sh $S verify --gateway $GW --key mqk_live_…
```

## 校验规则（照抄自真实事故，不是发明）

- **凭据平面不通用**：`/v1` 推理面只认 `mqk_live_…`，MCP 面只认消费者凭据（`mqk_api_…` 或 JWT）——
  用错一律 401（统一反枚举）。工具在写/打印前直接拦下并说明（`docs/operations-runbook.md` §14.1）。
- **虚拟密钥必须带 `.label` 后缀**：CAA 解析器要求 `mqk_live_<id>.<label>` 形态，裸钥
  （无点号）语法上就不成立，数据面统一回 404 `virtual_key_invalid`。裸钥直接拒用并点名该陷阱。
- **网关地址是 origin**：`https://` 必填（仅 127.0.0.1/localhost 放行 http）；尾随 `/` 归一化；
  尾随 `/v1` 会带提示剥离（客户端各自会追加 `/v1/…`，带上会双重拼接）。

## 托管块与幂等

`apply env` / `apply dotenv` 在目标文件中维护一段托管块：

```
# >>> miqro-onboard (managed) >>>
…注入内容…
# <<< miqro-onboard (managed) <<<
```

重复执行只替换块内内容；内容无变化时不写盘、不产生备份。块外的行原样保留。

> `.env` 解析器通常忽略 `#` 注释行，托管块标记对 dotenv 形态同样安全。

## 密钥卫生

写入的目标文件与其备份**都置 0600**（内容含密钥）；目标若位于 git 工作树内且未被忽略，会警告
"可能被误提交"。Windows（NTFS）不携带 POSIX 模式、chmod 为 no-op，请依赖目录 ACL；测试对
该平台**显式 SKIP 模式断言**（不假装验证过），Linux/CI 上强制执行。

## 与前端的一致性

snippet 形态（三种 shell 的 env 块、`settings.json` 片段、Codex TOML、OpenAI 兼容提示、
MCP 配置）以控制台「使用密钥」面板为准，源头是 `frontend/src/lib/ccswitch.ts`。
**改任一侧时同步另一侧**；本工具的输出形状有测试固定（`test-onboard.sh`）。

## 测试

```bash
sh scripts/onboarding/test-onboard.sh
```

纯 POSIX sh、无网络（`verify` 用 PATH 前置的假 curl）；覆盖打印、校验拒绝路径、
托管块幂等/替换/备份、dry-run、`claude-settings` 合并（有 jq 时）与 verify 的 200/404/401 归因。
