# 真实供应商联调指南

> 2026-08-30 首次执行（DeepSeek 官方 Key）全链路通过。本文固化环境搭建、流程与踩坑记录；腾讯凭证到位后按此复用。

## 1. 环境搭建（一次性）

```bash
# PostgreSQL（Docker）
docker compose -f deploy/compose.yaml up -d

# 密钥文件（32 字节各一，bootstrap secret 16+ 字符）
# 路径示例：D:\...\miqro-local\keys\{master-key.bin,hmac-key.bin,bootstrap-secret.txt}
python -c "import secrets; open('master-key.bin','wb').write(secrets.token_bytes(32))"

# 先 install 依赖模块（spring-boot:run 单模块需要）
./mvnw -f backend/pom.xml install -DskipTests

# control-plane（8080）—— 环境变量见下
nohup ./mvnw -f backend/pom.xml -pl control-plane-app spring-boot:run &
# gateway（8081）
nohup ./mvnw -f backend/pom.xml -pl gateway-app spring-boot:run &
```

**control-plane 环境变量**（易错点）：
```
MIQROKEY_DB_PASSWORD=...
MIQROKEY_CRYPTO_ENABLED=true
MIQROKEY_CRYPTO_ENCRYPTION_ACTIVE_VERSION=v1
MIQROKEY_CRYPTO_ENCRYPTION_VERSIONS_V1=<master-key.bin 绝对路径>   # 注意不是 ENC_KEY_FILE！
MIQROKEY_CRYPTO_HMAC_ACTIVE_VERSION=v1
MIQROKEY_CRYPTO_HMAC_VERSIONS_V1=<hmac-key.bin 绝对路径>
MIQROKEY_BOOTSTRAP_SECRET_FILE=<bootstrap-secret.txt 绝对路径>
```

**gateway 环境变量**：
```
MIQROKEY_GATEWAY_DB_PASSWORD=...
MIQROKEY_CRYPTO_ENABLED=true
MIQROKEY_CRYPTO_ENC_KEY_FILE=<master-key.bin>     # gateway 用 ENC_KEY_FILE 命名
MIQROKEY_CRYPTO_HMAC_KEY_FILE=<hmac-key.bin>
```

> ⚠️ 不要用 `taskkill //F //IM java.exe` 杀进程 —— 会同时杀掉两个服务。用端口精确定位 PID：`netstat -ano | grep ":8080"` 后 `taskkill //F //PID <pid>`。

## 2. 联调流程（脚本化）

完整脚本见仓库外 `miqro-local/drill2.py`（不入库），核心步骤：

1. `POST /api/v1/auth/bootstrap`（字段名 **bootstrapSecret**，不是 secret）
2. 改密 → 登录（cookie 需**手动从 Set-Cookie 提取**，urllib cookie jar 不捕获）
3. 创建订阅（providerProductId 来自 seed 后的目录）
4. 创建凭证（真实 Key 明文只进请求体）
5. 创建项目 → Grant（项目+产品+凭证+模型）
6. 创建 Virtual Key → `POST :8081/v1/chat/completions`（Authorization: Bearer <vk>）
7. 验证 `GET /api/v1/me/usage/summary`（用量落库）+ 录单价后验证成本

**已验证的关键行为**：
- 创建 VK 后 **NOTIFY 即时刷新 ~4s 生效**（无需等 30s 定时）
- 用量解析精确：DeepSeek `prompt_cache_miss_tokens` → `cacheCreation` 正确映射
- 成本按单价快照精确：¥0.000128 = 16×2/1M + 8×8/1M + 16×2/1M

## 3. 踩坑记录（按坑排序）

| 坑 | 症状 | 原因 | 修复 |
|---|---|---|---|
| 所有带 session 请求 500 | `ScopeNotActiveException` | SessionFilter `HIGHEST_PRECEDENCE` 跑在 RequestContextFilter(-105) 之前 | order=-100（已修+回归测试） |
| bootstrap 400 `VALIDATION_FAILED` | fieldErrors bootstrapSecret INVALID | 字段名是 `bootstrapSecret` 不是 `secret` | 用对字段名 |
| crypto bean 找不到 | `KeyEncryptionProvider` 无 | control-plane 需 `MIQROKEY_CRYPTO_ENABLED=true` + `ENCRYPTION_VERSIONS_V1`（非 ENC_KEY_FILE） | 见环境变量 |
| `users already exist` 残留 | bootstrap 401 | 清库需按 FK 顺序（sessions→users→audit）| 用完整 DELETE 顺序 |
| 推理 404 `Unknown virtual key` | 刚建的 VK 不认 | 之前是环境干扰（残留 gateway）；干净环境 NOTIFY 4s 生效 | 确保只跑一个 gateway |
| cookie 抓不到 | urllib jar 空 | 手动从 Set-Cookie 解析 | 见 drill2.py 的 raw() |

## 4. 安全注意

- 真实 Key **绝不入库/入日志/入聊天**：从临时文件读（用完即删），只进进程环境
- Key 一旦在会话中出现过，**立即去供应商控制台轮换**
- GitHub secret scanning 建议开启（Settings → Code security），双保险

## 5. 腾讯联调清单（复用本指南）

1. 拿到腾讯云 TokenHub 凭证（Coding Plan 或 Token Plan 产品）
2. 按 §1 起环境 → §2 跑 drill2.py（换产品 code 与 Key）
3. 重点验证（首次真实验证）：
   - Anthropic 兼容入口鉴权头（文档未明示，按惯例 Bearer 实现 —— 真实调用确认）
   - `/models` 探活端点（腾讯产品可能无此端点）
   - 适配器标记从 `IMPLEMENTED` 升级 `VERIFIED` 的前提（真实契约测试通过）
