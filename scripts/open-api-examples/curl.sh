#!/usr/bin/env bash
# 管理开放 API curl 示例（ADR-0015/0016）。
# 先由 SYSTEM_ADMIN 网页会话发行机器密钥，再替换下方占位符运行。
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
TOKEN="${TOKEN:-mqk_admin_CHANGE_ME}"

AUTH="Authorization: Bearer ${TOKEN}"
CT="Content-Type: application/json"

echo "== 1) 用量汇总（读面） =="
curl -fsS -H "$AUTH" "$BASE/api/v1/admin-api/usage/summary?groupBy=project" | head -c 400; echo

echo "== 2) 审计尾（读面） =="
curl -fsS -H "$AUTH" "$BASE/api/v1/admin-api/audit-events?size=5" | head -c 400; echo

echo "== 3) 建告警规则（写面 C） =="
curl -fsS -X POST -H "$AUTH" -H "$CT" \
  -d '{"name":"用量缺失率-自动","type":"USAGE_MISSING_RATE","threshold":0.05,"dedupeMinutes":30}' \
  "$BASE/api/v1/admin-api/alert-rules" | head -c 300; echo

echo "== 4) 建 Webhook 端点（写面 C） =="
curl -fsS -X POST -H "$AUTH" -H "$CT" \
  -d '{"name":"ops-alerts","url":"https://example.com/hook","secret":"whsec-CHANGE_ME","timeoutMs":5000}' \
  "$BASE/api/v1/admin-api/webhooks" | head -c 300; echo

echo "== 5) 建导出任务（写面 A：created_by=发行管理员） =="
FROM="$(date -u -d '2 days ago' +%Y-%m-%dT%H:%M:%SZ)"
TO="$(date -u -d '1 day ago' +%Y-%m-%dT%H:%M:%SZ)"
curl -fsS -X POST -H "$AUTH" "$BASE/api/v1/admin-api/export-tasks?format=CSV&from=$FROM&to=$TO" | head -c 300; echo

echo "== 6) 吊销这把密钥（SYSTEM_ADMIN 网页会话执行,此处仅示例调用形态） =="
echo "# POST /api/v1/admin/api-keys/{id}/revoke 需要会话 + CSRF,不由机器钥自吊销"
