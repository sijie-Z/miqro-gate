# 管理开放 API 示例（batch 2, ADR-0015/0016）

对 `POST /api/v1/admin/api-keys`（网页会话发行）取得的机器密钥，用
`Authorization: Bearer mqk_admin_…` 调用 `/api/v1/admin-api/**`。

- `curl.sh` — bash/curl 最小流程（读面 → 写面 v1 → Virtual Key 委托建钥 → 吊销前确认）
- `example.py` — 同一流程的 Python（仅标准库）
- 两个脚本都只含占位符，不携带真实密钥

## 最小权限建议

- 机器密钥**身份即委托**：密钥的写操作记为「发行它的管理员」执行（ADR-0016 A），
  审计可沿密钥回到人——所以请用**专用管理员账号**发行，不要用日常账号。
- 命名约定：按用途命名（如 `ops-usage-reader`、`ci-alert-manager`），便于吊销定位；
  吊销即时生效，例行轮换建议 30 天一次（发行新钥 → 迁移调用 → 吊销旧钥）。
- 默认全量密钥可读该租户开放读面并可写 alert-rules/webhooks/export-tasks；可代
  项目成员建 Virtual Key（批 2 v2，ADR-0016 增补 2026-09-09）。**批 3 作用域
  （只读/写/端点白名单）落地前**，请只把密钥交给可信自动化，并把脚本放进带审计的
  CI/调度环境。
- 委托建钥额外注意：目标用户须为该**项目的成员**（先加成员再建钥，与网页流程一致）；
  钥归属目标用户、审计记「委托人 + targetUserId」——自动化开号流程应保留这两层信息。
- 不要把这把密钥当 Virtual Key 使用；内容数据面调用仍走 Virtual Key 体系。
