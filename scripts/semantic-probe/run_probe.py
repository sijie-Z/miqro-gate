#!/usr/bin/env python3
"""Offline probe-set evaluation for semantic-cache threshold calibration (ADR-0022 §11 D1/D2, P1 phase one).

Reads a probe set of (A, B) text pairs — paraphrases that MUST hit, and hard
negatives that MUST NOT — embeds both sides, and reports the two cosine
similarity distributions plus the candidate thresholds they imply. Zero customer
data, zero gateway involvement: this is the offline half of P1 (the sampled
in-process shadow measurement is the other half).

Three-zone reading (ADR-0022 §7 / evaluation v2 §3.2):
    score <  T_low             MISS          (not recorded)
    T_low <= score < T_high    SHADOW-ONLY   (counted, never replayed)
    score >= T_high            high-confidence candidate

Calibration order is deliberate: press false positives first (target FPR over
the hard negatives), then recover recall — see the probe-set spec.

Backends:
    st      sentence-transformers (local models: BAAI/bge-*-zh-v1.5, Qwen/Qwen3-Embedding-*)
    openai  any OpenAI-compatible /embeddings endpoint (e.g. DashScope compatible-mode
            for text-embedding-v4)

Examples:
    uv run --with sentence-transformers --with numpy python run_probe.py \
        --backend st --model BAAI/bge-base-zh-v1.5
    DASHSCOPE_API_KEY=... uv run --with numpy python run_probe.py --backend openai \
        --base-url https://dashscope.aliyuncs.com/compatible-mode/v1 \
        --model text-embedding-v4 --api-key-env DASHSCOPE_API_KEY
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import platform
import statistics
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
DEFAULT_PROBES = HERE / "probes.jsonl"
FPR_POINTS = (0.0, 0.005, 0.01, 0.02, 0.05)


def load_probes(path: Path) -> list[dict]:
    rows = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        row = json.loads(line)
        for field in ("id", "label", "dim", "a", "b", "reason"):
            if not row.get(field):
                sys.exit(f"{path}:{lineno}: missing field {field!r}")
        if row["label"] not in ("positive", "hard_negative"):
            sys.exit(f"{path}:{lineno}: unknown label {row['label']!r}")
        rows.append(row)
    if not rows:
        sys.exit(f"{path}: no probes")
    return rows


def embed_st(texts: list[str], model_name: str, batch_size: int) -> np.ndarray:
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError:
        sys.exit("backend st needs sentence-transformers: uv run --with sentence-transformers ...")
    model = SentenceTransformer(model_name)
    vecs = model.encode(
        texts, batch_size=batch_size, normalize_embeddings=True, convert_to_numpy=True
    )
    vecs = np.asarray(vecs, dtype=np.float64)
    # Normalize again here: `normalize_embeddings=True` is not honoured by every
    # model configuration (Qwen3-Embedding-0.6B returned cosines above 1.0), and
    # the whole calibration assumes unit vectors so that dot == cosine.
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    return vecs / np.where(norms == 0, 1.0, norms)


def embed_openai(
    texts: list[str], model_name: str, base_url: str, api_key: str, batch_size: int
) -> np.ndarray:
    out: list[list[float]] = []
    url = base_url.rstrip("/") + "/embeddings"
    for start in range(0, len(texts), batch_size):
        chunk = texts[start : start + batch_size]
        body = json.dumps({"model": model_name, "input": chunk}).encode("utf-8")
        req = urllib.request.Request(
            url,
            data=body,
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        )
        with urllib.request.urlopen(req, timeout=120) as resp:  # noqa: S310 - operator-supplied endpoint
            payload = json.loads(resp.read())
        items = sorted(payload["data"], key=lambda d: d["index"])
        out.extend(item["embedding"] for item in items)
    vecs = np.asarray(out, dtype=np.float64)
    norms = np.linalg.norm(vecs, axis=1, keepdims=True)
    return vecs / np.where(norms == 0, 1.0, norms)


def quantile(values: np.ndarray, q: float) -> float:
    return float(np.quantile(values, q, method="linear"))


def describe(values: np.ndarray) -> dict:
    return {
        "n": int(values.size),
        "min": float(values.min()),
        "p05": quantile(values, 0.05),
        "p25": quantile(values, 0.25),
        "median": float(np.median(values)),
        "mean": float(values.mean()),
        "sd": float(values.std(ddof=1)) if values.size > 1 else 0.0,
        "p75": quantile(values, 0.75),
        "p95": quantile(values, 0.95),
        "max": float(values.max()),
    }


def auc(pos: np.ndarray, neg: np.ndarray) -> float:
    """P(random positive > random negative), ties at 0.5 (rank-based)."""
    both = np.concatenate([pos, neg])
    order = both.argsort(kind="mergesort")
    ranks = np.empty_like(order, dtype=np.float64)
    ranks[order] = np.arange(1, both.size + 1)
    # average ranks over ties
    sorted_vals = both[order]
    i = 0
    while i < sorted_vals.size:
        j = i
        while j + 1 < sorted_vals.size and sorted_vals[j + 1] == sorted_vals[i]:
            j += 1
        if j > i:
            ranks[order[i : j + 1]] = (i + j + 2) / 2.0
        i = j + 1
    pos_rank_sum = ranks[: pos.size].sum()
    n1, n0 = pos.size, neg.size
    return float((pos_rank_sum - n1 * (n1 + 1) / 2.0) / (n1 * n0))


def lexical_similarity(a: str, b: str) -> float:
    """Character-level sequence similarity (difflib ratio) — the surface baseline cosine must beat.

    Short technical prompts that differ by one value (DEBUG vs WARN, 3000ms vs
    300ms) are near-identical to *any* string metric; a semantic score is only
    interesting where it separates classes the surface metric cannot. Character
    n-gram Jaccard was tried first and rejected: it collapses on short CJK
    strings (a one-word swap drops it below 0.6), so it cannot express "these
    two differ by one token".
    """
    return difflib.SequenceMatcher(None, a, b).ratio()


LEXICAL_BANDS = ((0.0, 0.2), (0.2, 0.4), (0.4, 0.6), (0.6, 0.8), (0.8, 1.0001))


def banded_auc(scores: np.ndarray, labels: np.ndarray, lexical: np.ndarray) -> list[dict]:
    """AUC recomputed inside lexical-overlap bands (surface form held ~constant)."""
    bands = []
    for lo, hi in LEXICAL_BANDS:
        mask = (lexical >= lo) & (lexical < hi)
        n_pos = int((mask & labels).sum())
        n_neg = int((mask & ~labels).sum())
        entry = {"lo": lo, "hi": min(hi, 1.0), "n_positive": n_pos, "n_hard_negative": n_neg}
        if n_pos >= 3 and n_neg >= 3:
            entry["auc_within_band"] = auc(scores[mask & labels], scores[mask & ~labels])
        bands.append(entry)
    return bands



def operating_points(pos: np.ndarray, neg: np.ndarray) -> list[dict]:
    points = []
    for fpr in FPR_POINTS:
        threshold = float(neg.max()) if fpr == 0.0 else quantile(neg, 1.0 - fpr)
        recall = float((pos >= threshold).mean())
        precision = 0.0
        if recall > 0:
            n_hit = int((pos >= threshold).sum())
            n_fp = int((neg >= threshold).sum())
            precision = n_hit / (n_hit + n_fp) if (n_hit + n_fp) else 0.0
        points.append(
            {
                "target_fpr": fpr,
                "threshold": threshold,
                "observed_fpr": float((neg >= threshold).mean()),
                "recall": recall,
                "precision_on_probe_set": precision,
            }
        )
    return points


def histogram(pos: np.ndarray, neg: np.ndarray, lo: float = 0.0, hi: float = 1.0, bins: int = 25):
    edges = np.linspace(lo, hi, bins + 1)
    pos_counts, _ = np.histogram(pos, bins=edges)
    neg_counts, _ = np.histogram(neg, bins=edges)
    return [
        {
            "lo": float(edges[i]),
            "hi": float(edges[i + 1]),
            "positive": int(pos_counts[i]),
            "hard_negative": int(neg_counts[i]),
        }
        for i in range(bins)
    ]


def slugify(model: str) -> str:
    return model.replace("/", "_").replace(":", "_")


def render_report(result: dict) -> str:
    meta, pos, neg = result["meta"], result["positive"], result["hard_negative"]
    lex = result["lexical"]
    lines = [
        f"# 语义探针评测结果 · {meta['model']}",
        "",
        f"- 运行时间：{meta['timestamp_utc']}（UTC）",
        f"- 后端：{meta['backend']}；向量维度：{meta['dim']}",
        f"- 探针集：`{meta['probes_file']}`（sha256 `{meta['probes_sha256'][:12]}`，{meta['pairs']} 对）",
        "",
        "## 1. 相似度分布",
        "",
        "| 类别 | n | min | p05 | p25 | 中位 | 均值 | p75 | p95 | max |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for name, stats in (("正例", pos), ("硬负例", neg)):
        lines.append(
            f"| {name} | {stats['n']} | {stats['min']:.4f} | {stats['p05']:.4f} | {stats['p25']:.4f} "
            f"| {stats['median']:.4f} | {stats['mean']:.4f} | {stats['p75']:.4f} "
            f"| {stats['p95']:.4f} | {stats['max']:.4f} |"
        )
    lines += [
        "",
        f"- 分离度 AUC = **{result['auc']:.4f}**（随机取一正一负、正例分数更高的概率；0.5 = 无区分力）",
        f"- 重叠区：负例 max = {neg['max']:.4f}，正例 p05 = {pos['p05']:.4f}",
        "",
        "### 1.1 表面重叠对照（先看这个，再看阈值）",
        "",
        f"字面指标为字符级 difflib 相似度：正例中位 **{lex['positive']['median']:.4f}**、硬负例中位 "
        f"**{lex['hard_negative']['median']:.4f}**；字面指标自身 AUC = {lex['auc']:.4f}。",
        "",
        "| 表面重叠区间 | 正例 n | 硬负例 n | 区间内 AUC |",
        "|---|---|---|---|",
    ]
    for band in lex["bands"]:
        within = (
            f"{band['auc_within_band']:.4f}"
            if "auc_within_band" in band
            else "样本不足"
        )
        lines.append(
            f"| {band['lo']:.1f}–{band['hi']:.1f} | {band['n_positive']} | {band['n_hard_negative']} | {within} |"
        )
    lines += [
        "",
        "> 读法：**区间内 AUC** 才是「embedding 是否超出字面匹配」的证据。整体 AUC 会被两类的字面重叠差异污染——"
        "若硬负例整体比正例更像（字面 Jaccard 更高），任何偏字面的分数都会系统性抬高负例，整体 AUC 甚至可能低于 0.5。"
        "在同样的字面重叠下，区间内 AUC 明显高于 0.5 才说明该档模型仍有语义分辨力；接近 0.5 或更低，说明它的区分力不超过字面匹配。",
        "",
        "## 2. 工作点（先压误命中，再谈召回）",
        "",
        "| 目标误命中率 | 阈值 | 实测误命中率 | 召回（探针集） | 精确率（探针集） |",
        "|---|---|---|---|---|",
    ]
    for point in result["operating_points"]:
        lines.append(
            f"| {point['target_fpr']:.1%} | {point['threshold']:.4f} | {point['observed_fpr']:.1%} "
            f"| {point['recall']:.1%} | {point['precision_on_probe_set']:.1%} |"
        )
    lines += [
        "",
        "> 精确率按**探针集**的正负 1:1 配比计算；生产流量的正负比远低于此，真实精确率会更低，"
        "因此阈值选择应以**误命中率**为准（本表第二、三列），不要直接引用精确率一列。",
        "",
        "## 3. 相似度直方图",
        "",
        "| 区间 | 正例 | 硬负例 |",
        "|---|---|---|",
    ]
    for bucket in result["histogram"]:
        lines.append(f"| {bucket['lo']:.2f}–{bucket['hi']:.2f} | {bucket['positive']} | {bucket['hard_negative']} |")
    lines += [
        "",
        "## 4. 风险清单（决策时优先看这两张表）",
        "",
        f"### 4.1 分数最高的硬负例（若阈值设低了，最先被误命中的就是这些）",
        "",
        "| id | 维度 | 相似度 | A | B | 错在哪 |",
        "|---|---|---|---|---|---|",
    ]
    for row in result["top_negative_hits"]:
        lines.append(
            f"| {row['id']} | {row['dim']} | {row['score']:.4f} | {row['a']} | {row['b']} | {row['reason']} |"
        )
    lines += [
        "",
        "### 4.2 分数最低的正例（阈值设高了会漏掉这些）",
        "",
        "| id | 维度 | 相似度 | A | B |",
        "|---|---|---|---|---|",
    ]
    for row in result["weakest_positives"]:
        lines.append(f"| {row['id']} | {row['dim']} | {row['score']:.4f} | {row['a']} | {row['b']} |")
    lines += [
        "",
        "## 5. 按维度拆分的硬负例分布",
        "",
        "| 维度 | n | 中位相似度 | max |",
        "|---|---|---|---|",
    ]
    for dim, stats in sorted(result["negative_by_dim"].items()):
        lines.append(f"| {dim} | {stats['n']} | {stats['median']:.4f} | {stats['max']:.4f} |")
    lines += ["", "## 6. 复现命令", "", "```bash", meta["command"], "```", ""]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--backend", choices=("st", "openai"), default="st")
    parser.add_argument("--model", default="BAAI/bge-base-zh-v1.5")
    parser.add_argument("--probes", type=Path, default=DEFAULT_PROBES)
    parser.add_argument("--out-dir", type=Path, default=HERE / "out")
    parser.add_argument("--tag", default="", help="suffix for output file names")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--prefix", default="", help="text prefixed symmetrically to A and B (model-specific instruction)")
    parser.add_argument("--base-url", default=os.environ.get("PROBE_OPENAI_BASE_URL", ""))
    parser.add_argument("--api-key-env", default="OPENAI_API_KEY")
    parser.add_argument("--top-k", type=int, default=12)
    args = parser.parse_args()

    rows = load_probes(args.probes)
    texts = [args.prefix + row[side] for row in rows for side in ("a", "b")]

    started = time.perf_counter()
    if args.backend == "st":
        vecs = embed_st(texts, args.model, args.batch_size)
    else:
        if not args.base_url:
            sys.exit("backend openai needs --base-url")
        api_key = os.environ.get(args.api_key_env, "")
        if not api_key:
            sys.exit(f"backend openai needs the env var {args.api_key_env}")
        vecs = embed_openai(texts, args.model, args.base_url, api_key, args.batch_size)
    elapsed = time.perf_counter() - started

    vec_a, vec_b = vecs[0::2], vecs[1::2]
    scores = np.einsum("ij,ij->i", vec_a, vec_b)

    labels = np.array([row["label"] == "positive" for row in rows])
    dims = [row["dim"] for row in rows]
    pos_scores, neg_scores = scores[labels], scores[~labels]
    if pos_scores.size == 0 or neg_scores.size == 0:
        sys.exit("probe set needs both positives and hard negatives")

    order_neg = np.argsort(-neg_scores)[: args.top_k]
    order_pos = np.argsort(pos_scores)[: args.top_k]
    lexical = np.array([lexical_similarity(row["a"], row["b"]) for row in rows])
    lex_pos, lex_neg = lexical[labels], lexical[~labels]
    neg_rows = [rows[i] for i in np.flatnonzero(~labels)]
    pos_rows = [rows[i] for i in np.flatnonzero(labels)]

    negative_by_dim: dict[str, dict] = {}
    for dim in sorted({d for d, keep in zip(dims, ~labels) if keep}):
        subset = np.array([s for s, d, keep in zip(scores, dims, ~labels) if keep and d == dim])
        negative_by_dim[dim] = describe(subset)

    result = {
        "meta": {
            "timestamp_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "backend": args.backend,
            "model": args.model,
            "prefix": args.prefix,
            "dim": int(vecs.shape[1]),
            "pairs": len(rows),
            "probes_file": args.probes.name,
            "probes_sha256": hashlib.sha256(args.probes.read_bytes()).hexdigest(),
            "embed_seconds": round(elapsed, 2),
            "batch_size": args.batch_size,
            "python": platform.python_version(),
            # Portable form on purpose: the artifacts are committed, and the
            # interpreter's absolute path would leak the operator's machine.
            "command": "python scripts/semantic-probe/run_probe.py " + " ".join(sys.argv[1:]),
        },
        "positive": describe(pos_scores),
        "hard_negative": describe(neg_scores),
        "auc": auc(pos_scores, neg_scores),
        "lexical": {
            "metric": "difflib character ratio",
            "positive": describe(lex_pos),
            "hard_negative": describe(lex_neg),
            "auc": auc(lex_pos, lex_neg),
            "bands": banded_auc(scores, labels, lexical),
        },
        "operating_points": operating_points(pos_scores, neg_scores),
        "histogram": histogram(pos_scores, neg_scores),
        "negative_by_dim": negative_by_dim,
        "pairs": [
            {
                "id": row["id"],
                "label": row["label"],
                "dim": row["dim"],
                "score": float(score),
                "lexical": float(lex),
            }
            for row, score, lex in zip(rows, scores, lexical)
        ],
        "top_negative_hits": [
            {**{k: neg_rows[i][k] for k in ("id", "dim", "a", "b", "reason")}, "score": float(neg_scores[i])}
            for i in order_neg
        ],
        "weakest_positives": [
            {**{k: pos_rows[i][k] for k in ("id", "dim", "a", "b")}, "score": float(pos_scores[i])}
            for i in order_pos
        ],
    }

    args.out_dir.mkdir(parents=True, exist_ok=True)
    suffix = f"_{args.tag}" if args.tag else ""
    stem = f"probe_{slugify(args.model)}{suffix}"
    (args.out_dir / f"{stem}.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    report = render_report(result)
    (args.out_dir / f"{stem}.md").write_text(report, encoding="utf-8")

    print(report)
    print(f"\nwritten: {args.out_dir / f'{stem}.json'} | {args.out_dir / f'{stem}.md'}")


if __name__ == "__main__":
    main()
