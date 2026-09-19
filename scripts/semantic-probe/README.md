# 语义探针评测（semantic probe）

离线跑具：用**全合成数据**回答一个问题——「换一种说法问同一问题」在 embedding 空间里能否被可靠区分，
并据此校准语义缓存的三区阈值（T_low / T_high）。

这是 ADR-0022 §11（所有者拍板 D1/D2）批准的 **P1 第一阶段**：零合规面，不接触任何客户内容、不改网关行为、
不用网关内流量。本地模型在本机跑；只有显式指定云端点时正文才会离开本机（评测对照用，按 ADR-0022 选项 C 口径）。

## 文件

| 文件 | 说明 |
|---|---|
| `probes.jsonl` | 探针集：128 对（64 正例 + 64 hard negative，其中 8 对为长句样本），全合成 |
| `run_probe.py` | 跑具：本地 sentence-transformers 模型，或任意 OpenAI 兼容 embedding 端点 |
| `out/` | 运行产物（JSON + Markdown 报告），不入库 |

## 用法

本地模型：

```bash
uv run --with sentence-transformers --with numpy python scripts/semantic-probe/run_probe.py \
    --backend st --model BAAI/bge-base-zh-v1.5
```

云端点（示例：阿里云百炼 `text-embedding-v4`，需自备密钥）：

```bash
DASHSCOPE_API_KEY=... uv run --with numpy python scripts/semantic-probe/run_probe.py \
    --backend openai --base-url https://dashscope.aliyuncs.com/compatible-mode/v1 \
    --model text-embedding-v4 --api-key-env DASHSCOPE_API_KEY
```

模型下载遵循 `HF_ENDPOINT`（网络受限时可设 `https://hf-mirror.com`）。产物默认写到 `out/`，
也可用 `--out-dir` / `--tag` 指定；JSON 里记录了模型、维度、探针集 sha256 与复现命令。

## 探针集约定

- `label`：`positive`（同义，可安全复用同一条答案）／`hard_negative`（表面极像但复用会出错；`reason` 必填，写明错在哪）。
- `dim`：正例为 `paraphrase` 或 `long`；hard negative 覆盖六个维度——`file`（文件/对象）、`direction`（方向）、
  `param`（参数值）、`language`（输出语言）、`env`（环境/版本）、`task`（任务类型）。
- `lang`：`zh` / `mixed` / `en`；`domain`：`code` / `ops` / `general`。
- 长句样本 108–228 字，用于观察长度对相似度的影响。

扩容规则（128 → 300–500，照 ADR-0022 §3.1）：

1. 来源：模板手工扩写（每批两类各半）＋**公开语料**改写（公开 issue 标题、社区常见问法）；**绝不使用网关内流量**；
2. hard negative 六维各补到 ≥12 条；正例占比 ≥45%；
3. 不允许两条正例互为改写、又与同一条 hard negative 相似（避免分布污染）；
4. 新增条目必须有 `reason`，含糊即退回。

## 判读口径

- **先压误命中**（hard negative 越过阈值的比例 → 0），再在误命中上限内提高召回；**不照抄任何厂商默认阈值**。
- 报告里的「精确率」按探针集 1:1 配比计算，**不能外推生产精确率**（生产里正负比远低于 1:1）；
  选阈值只看「误命中率」与「召回」两列。
- 三区（ADR-0022 §7 / 评估 v2 §3.2）：`< T_low` 未命中；`[T_low, T_high)` 仅影子计数、永不回放；
  `≥ T_high` 才可进入语义命中候选。

## 结论去哪

运行产出的报告归入 `docs/`（见对应 PR）；结论回填 ADR-0022 §11.2，是否进入 P2（B/B2 选型）由所有者拍板。
