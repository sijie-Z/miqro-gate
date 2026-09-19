# 2026-09-19 运行产物（P1 阶段一）

三档本地模型在同一 148 对 probe 集上的原始输出：`*.md` 为人读报告，`*.json` 含逐对分数（`pairs`）、区间内 AUC（`lexical.bands`）与完整工作点。

- 生成方式：`python scripts/semantic-probe/run_probe.py --backend st --model <模型>`
- 文件名后缀：无后缀 = 模型默认用法；`*instr` = 按模型卡加**指令前缀**（`--prefix`）的对照实验（见报告 §2.4）——`meta.prefix` 记录了所用前缀
- probe 集版本：每个 JSON 的 `meta.probes_sha256`（与 `scripts/semantic-probe/probes.jsonl` 比对即可确认是否漂移）
- 结论与判读：[../semantic-cache-probe-phase1-2026-09-19.md](../semantic-cache-probe-phase1-2026-09-19.md)
