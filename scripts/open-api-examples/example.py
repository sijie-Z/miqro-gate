"""管理开放 API Python 示例（ADR-0015/0016/0016 增补,仅标准库）。

先由 SYSTEM_ADMIN 网页会话发行机器密钥,再设环境变量:
  export MQK_BASE=http://localhost:8080
  export MQK_TOKEN=mqk_admin_CHANGE_ME
  export MQK_TARGET_USER_ID=...   # 委托建钥的目标用户(需为项目成员)
  export MQK_PROJECT_ID=...       # 项目/产品/授权取自网页端或读面
  export MQK_PRODUCT_ID=...
  export MQK_GRANT_ID=...
  python example.py
"""
import json
import os
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = os.environ.get("MQK_BASE", "http://localhost:8080")
TOKEN = os.environ.get("MQK_TOKEN", "mqk_admin_CHANGE_ME")


def call(method: str, path: str, body: dict | None = None) -> dict | list:
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req) as resp:
        payload = resp.read()
        return json.loads(payload) if payload else {}


def main() -> None:
    print("== 1) 用量汇总 ==")
    print(call("GET", "/api/v1/admin-api/usage/summary?groupBy=project")["totals"])

    print("== 2) 最近审计 3 条 ==")
    for event in call("GET", "/api/v1/admin-api/audit-events?size=3"):
        print(" ", event["action"], event["createdAt"])

    print("== 3) 建告警规则 ==")
    rule = call(
        "POST",
        "/api/v1/admin-api/alert-rules",
        {"name": "usage-missing-auto", "type": "USAGE_MISSING_RATE", "threshold": 0.05, "dedupeMinutes": 30},
    )
    print(" rule id:", rule["id"], rule["type"])

    print("== 4) 建 Webhook 端点 ==")
    hook = call(
        "POST",
        "/api/v1/admin-api/webhooks",
        {"name": "ops-alerts", "url": "https://example.com/hook", "secret": "whsec-CHANGE_ME"},
    )
    print(" hook id:", hook["id"])

    print("== 5) 建导出任务(created_by=发行管理员) ==")
    now = datetime.now(timezone.utc)
    task = call(
        "POST",
        "/api/v1/admin-api/export-tasks?"
        + urllib.parse.urlencode(
            {
                "format": "CSV",
                "from": (now - timedelta(days=2)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "to": (now - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ"),
            }
        ),
    )
    print(" task id:", task["id"], "created_by:", task["createdBy"])

    print("== 6) 代指定用户建 Virtual Key(批2 v2:钥归属目标,secret 仅此一次) ==")
    # 目标用户需是该项目的成员;委托人须为发行本密钥的 SYSTEM_ADMIN。
    # 环境变量: MQK_TARGET_USER_ID / MQK_PROJECT_ID / MQK_PRODUCT_ID / MQK_GRANT_ID
    vkey = call(
        "POST",
        "/api/v1/admin-api/virtual-keys",
        {
            "userId": os.environ["MQK_TARGET_USER_ID"],
            "name": "ci-delegated",
            "projectId": os.environ["MQK_PROJECT_ID"],
            "providerProductId": os.environ["MQK_PRODUCT_ID"],
            "credentialGrantId": os.environ["MQK_GRANT_ID"],
            "purpose": "CLAUDE_CODE",
        },
    )
    print(" vkey id:", vkey["id"], "| secret 仅此一次返回,展示:", vkey["display"])

    print("== 7) 查该用户拥有的钥(无 secret) ==")
    keys = call("GET", "/api/v1/admin-api/virtual-keys?userId=" + os.environ["MQK_TARGET_USER_ID"])
    print(" count:", len(keys), "| first:", keys[0]["id"] if keys else "-")

    print("== 8) 清理演示对象(规则/端点;Virtual Key 吊销走网页会话) ==")
    call("DELETE", f"/api/v1/admin-api/alert-rules/{rule['id']}")
    call("DELETE", f"/api/v1/admin-api/webhooks/{hook['id']}")
    print(" done")


if __name__ == "__main__":
    main()
