# 留痕消费端契约与参考实现（ADR-0014 R4）

> 面向**平台侧消费端**（多进程、Kafka consumer group）的接口契约与参考实现。
> 本文档描述的是本网关（MiQroKey Gateway）产出的信封如何被消费、解密与落盘；
> 具体存储目标由平台决定——对象存储（SSE-KMS）为主、**本地文件滚动为私有化
> 降级**、平台数据库可选（ADR-0014 §0c M10/M11）。本文件 + `scripts/retention/
> consumer-file-ref.py` = 本地文件输出的参考实现（可整体照抄或适配其它目标）。

## 1. 拓扑与语义

```
MiQroKey Gateway ──(标准 Kafka 协议)──▶ topic content-retention
       记录键 = SHA-256(tenantId/userId)      ▼
                                   平台消费端 consumer group（多进程）
                                        │ at-least-once + 按 eventId 幂等
                                        ▼
                    输出目标之一：本地文件（按 user 分目录滚动）
                    其它：对象存储（SSE-KMS）/ 平台 DB（信封索引）
```

- **配置**：网关侧 `miqrokey.retention.kafka.bootstrap-servers`（设置后启用投递）、
  `miqrokey.retention.kafka.topic`（默认 `content-retention`）、`client-id`。消费端
  用自己的 group id；topic 由 broker 策略建（生产默认自动创建单分区即可用；
  多分区时同用户恒落同分区——按用户追溯天然分组且保序）。
- **投递语义**：at-least-once（网关异步发送 + 失败计数；消费端必须幂等）。
  幂等键 = `eventId`（UUID v4）。副本/重放由 broker 与网关重试造成，属预期。
- **触发条件**：租户 `retention_config.enabled=true` 且协议匹配且请求含用户文本，
  且正文已被网关侧 AES 加密——**topic 上永远只有密文**（base64），绝无明文用户正文。
- **保序**：同一 `(tenant,user)` 的所有事件恒落同一分区 → 分区内有序，消费组内
  单分区顺序保证；跨分区无全局序（无此需求）。

## 2. 信封 JSON Schema（value 字节 = UTF-8 JSON，与网关 producer 输出一致）

| 字段 | 类型 | 说明 |
|---|---|---|
| `eventId` | string (UUID) | 幂等键；重放识别 |
| `tenantId` | string (UUID) | 租户 |
| `userId` | string (UUID) | 本系统用户（virtual key 属主） |
| `virtualKeyId` | string (UUID) | 触发请求的 Virtual Key |
| `wireProtocol` | string | `ANTHROPIC_MESSAGES` / `OPENAI_CHAT` / `OPENAI_RESPONSES` |
| `gatewayRequestId` | string | 网关内部请求 ID（与 F15 访问日志、usage 记录可关联） |
| `occurredAt` | string (ISO-8601 UTC) | 事件时间 |
| `keyVersion` | string | 密文所用密钥版本（如 `v1`），解密方按版本取密钥 |
| `textCharCount` | int | 明文用户文本字符数（加密前计数，非密文长度；截断事件计已捕获部分的字符数） |
| `ciphertext` | string (base64) | AES-GCM 密文（**仅用户 role 文本**：P1 USER_TEXT_ONLY） |
| `nonce` | string (base64) | AES-GCM IV（12B） |

记录 key 为 64-hex（`SHA-256(tenant/user)`）——消费端如需「按用户重组」可直接按
key 分组，无需解析 value；value 内 `userId` 为规范字段。

### 解密说明（授权读者）

信封用与上游凭证相同的 **AES-256-GCM** 信封加密链加密，AAD 绑定
`(tenantId, credentialId=RETENTION_AAD_ID)`，其中

```
RETENTION_AAD_ID = UUID.nameUUIDFromBytes("miqro-retention-envelope")
```

是合成常量（网关代码注释同源，永不对应真实凭证）。解密端必须使用与
`keyVersion` 一致的密钥材料 + 上述 AAD 常量调用同一 `KeyEncryptionProvider` 语义；
明文为 UTF-8 用户文本（多条 user 消息以 `\n---\n` 连接，见抽取器约定）。密钥管理
为平台侧职责（P5：KMS/中心化密钥，按版本轮换；本仓库不持解密密钥材料）。

## 3. 本地文件输出布局（参考实现语义）

```
<root>/<tenantId>/<userId>/<YYYY-MM-DD>.jsonl
```

- 每行一个信封 JSON（原样 value 字节 decode 后追加 `\n`）。
- 按用户分目录 + 按天滚动（私有化降级默认布局；可按平台要求改）。
- 幂等：参考实现用内存集合 + 启动时扫描既有行中的 `eventId` 防重启重复；
  平台正式实现应在落库场景用唯一约束（如 `PRIMARY KEY (event_id)`）。
- 提交：处理完一批并**成功落盘后**手动提交 offset（`enable.auto.commit=false`），
  崩溃最多导致重放 → 由幂等吸收。
- 保留期/过期清理 = 平台侧策略（ADR-0014 P3：保留期由平台决定；本地文件默认不
  自动删，只提示）。
- 权限：目录/文件权限最小化（含密文，仍按敏感数据处理）；参考实现不写明文。

## 4. 参考实现使用（scripts/retention/consumer-file-ref.py）

```bash
pip install -r scripts/retention/requirements.txt        # kafka-python
python scripts/retention/consumer-file-ref.py \
  --bootstrap-servers 127.0.0.1:9092 \
  --topic content-retention \
  --group retention-file-ref \
  --root ./retention-out \
  --dry-run
```

参数、行为与退出方式见脚本头部 docstring；`--dry-run` 只打印解码后的信封摘要，
不落盘（联调首选）。与网关同库跑本地冒烟的最小链路：

1. 网关配置 `miqrokey.retention.kafka.bootstrap-servers` 并开启某租户留痕；
2. 用任一 LLM 请求命中（用户消息）；或直接跑网关 `RetentionKafkaIntegrationTest`
   观察同一 topic；
3. 参考消费者落盘后检查 `retention-out/<tenant>/<user>/*.jsonl`，并核对
   `ciphertext` 为 base64 密文、`plaintext` 永不出现在任何文件中。

> 状态：**REFERENCE**（平台侧适配起点）。本仓库产品门禁不执行 Python；逻辑正确性
> 由上述手工冒烟与网关端 Redpanda 集成测试共同保证。平台侧改造为对象存储/DB
> 输出时，Schema §2 与幂等 §1 语义不变。
