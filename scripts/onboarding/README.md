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

## 安全姿态（#763 评审后收紧）

这个工具**生成的是别的程序会执行/解析的配置**，所以每个要落进输出的值都过两道：

1. **字符集白名单**（可测的边界）：凭据 `A-Za-z0-9._-`、model `A-Za-z0-9._:/-`、网关
   host[:port] 同上一族、mcp-url 同 model 一族——**域外字符一律拒绝**（含引号、`$`、
   反引号、空白、换行）。
2. **按目标语法转义**（第二层）：POSIX shell 单引号包裹（内部 `'` 以 `'\''` 展开）、
   PowerShell 反引号转义 `$`/`"`/`` ` ``、JSON/TOML 反斜杠与引号转义。
   **cmd.exe 没有可靠的转义**——含 `& | < > ^ % ! " \` 的值会**拒绝输出**并提示改用
   posix/powershell（宁可拒发，不发一条"粘贴后可能做别的事"的片段）。

与之配套的几条：

- **密钥不进注释、不必进命令行**：Codex 片段**不再**把真实 key 写进 TOML 注释（写入的
  配置文件会被 grep/索引/备份/传票据带走）；`--key -` 从 stdin 读凭据，避免进入
  shell history 与进程列表。`print` 会把 key 显示给用户（这是它的用途），但落盘形态不夹带。
- **`verify` 的成功判定不只是 200**：要求 200 **且**响应体是 models list（含 `"data"`）——
  代理、WAF、错误页都会回 200；请求带 `--connect-timeout 5 --max-time 20`；失败默认只打印
  状态与解析出的 `code`（`--verbose` 才打印体）。
- **托管块策略从"猜"改成"拒"**：目标文件里 0 个标记→追加；恰好一对→替换；**标记残缺或成对
  重复→拒绝**（继续追加可能留下被解析两次的文件）。
- **只写普通文件**：目标是 symlink 时**拒绝**（否则替换会把引用关系静默换成普通文件）。
- **权限是承诺不是尽力**：写入文件与备份 `chmod 600` 失败即报错退出（不再"吞掉失败还说成功"）。
- **临时文件与目标同目录**：最终替换是同文件系统的原子 rename，不会退化成跨盘复制。

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

**当前有一处有意分叉**：Codex 片段——本工具按 #763 评审**不再把 key 写进 TOML 注释**，
前端对齐见 issue **#821**（对齐后此分叉即消失）。

## 测试

```bash
sh scripts/onboarding/test-onboard.sh
```

85 条断言、纯 POSIX sh、无网络（`verify` 用 PATH 前置的假 curl 并断言其参数含超时）。
覆盖：打印各形态、**恶意/畸形输入的拒绝**（引号、`$()`、cmd 元字符、userinfo/query 网关、
mcp-url 引号）、转义函数（经 `MIQRO_ONBOARD_SOURCE_ONLY=1` 源入直测）、托管块策略
（替换/幂等/残缺拒/重复拒）、symlink 拒、备份与权限（BSD/GNU `stat` 双写法）、
dry-run、`claude-settings` 合并（有 jq 时）与 verify 的 200（含"不像 models list"）/404/401/verbose。
新增用例均对修复前的脚本**先证过红**（旧脚本：引号 key 放行、codex 输出含 key、代理错误页 200 判成功）。
