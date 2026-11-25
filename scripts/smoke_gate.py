# -*- coding: utf-8 -*-
"""判据 4：至少一次投递 + 消费幂等。

两半各证明一次：
  (a) 正常路径：canary 一个发布 ⇒ outbox ⇒ Redis Stream ⇒ gate 消费 ⇒ 该发布恰好 1 个门禁任务
  (b) 重复投递：同一 eventId 手工投 100 次 ⇒ 仍然只有 1 个任务（uk_event 唯一索引挡掉）

再附一条可观测判据：消费完后 stream 的 pending 必须为 0（说明每条都 ack 了）。

用法：python smoke_gate.py
"""
from __future__ import annotations

import http.client
import json
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE, GATE = 8081, 8082, 8083
KEY, ENV = "policy_qa", "prod"
STREAM = "arg:releases"


def call(port: int, method: str, path: str, body=None, headers=None, timeout=30):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        text = r.read().decode("utf-8", "replace")
        return r.status, text
    finally:
        c.close()


def redis_cli(*args: str) -> str:
    r = subprocess.run(["docker", "exec", "arg-redis", "redis-cli", *args],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout.strip()


def sql(query: str) -> str:
    r = subprocess.run(["docker", "exec", "arg-mysql", "mysql", "-uroot", "-parg_root_2026",
                        "-N", "-B", "-e", query],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout.strip()


def drain_gate() -> int:
    """一直消费到没有新消息为止（显式触发，不靠 sleep 等定时器）。"""
    total = 0
    for _ in range(50):
        s, body = call(GATE, "POST", "/api/gate/consume")
        assert s == 200, (s, body)
        n = json.loads(body)["consumed"]
        total += n
        if n == 0:
            break
    return total


def main() -> int:
    ok = True
    print("== 0. 准备：一个配置版本 + 一次灰度发布 ==")
    s, body = call(CONFIG, "GET", f"/api/config/versions/latest?promptKey={KEY}")
    if s != 200:
        s, body = call(CONFIG, "POST", "/api/config/versions",
                       {"promptKey": KEY, "payload": {"model": "deepseek-chat"}, "createdBy": "smoke"})
    ver = json.loads(body)["version"]
    s, body = call(RELEASE, "POST", "/api/releases",
                   {"env": ENV, "promptKey": KEY, "version": ver, "operator": "smoke"})
    rel = json.loads(body)
    s, body = call(RELEASE, "POST", f"/api/releases/{rel['id']}/canary", {"rev": rel["rev"], "rolloutPct": 10})
    print(f"  release id={rel['id']} canary -> {s}")

    print("\n== 1. 等 outbox 投递（最多 300ms）+ 消费 ==")
    time.sleep(1.0)
    print(f"  stream 长度={redis_cli('XLEN', STREAM)}")
    n1 = drain_gate()
    print(f"  本轮消费 {n1} 条")

    s, body = call(GATE, "GET", f"/api/gate/jobs?releaseId={rel['id']}")
    jobs = json.loads(body)
    print(f"  该发布的门禁任务数 = {len(jobs)}（期望 1）")
    if jobs:
        print(f"    {json.dumps(jobs[0], ensure_ascii=False)[:160]}")
    ok &= (len(jobs) == 1)

    print("\n== 2. 判据 4b：同一 eventId 手工投 100 次 ==")
    dup = f"dup-{rel['id']}"
    for _ in range(100):
        redis_cli("XADD", STREAM, "*", "eventId", dup, "type", "RELEASE_CANARY",
                  "payload", json.dumps({"releaseId": rel["id"]}))
    print(f"  投完 stream 长度={redis_cli('XLEN', STREAM)}")
    n2 = drain_gate()
    print(f"  消费 {n2} 条（应 ≈100）")
    cnt = sql(f"SELECT COUNT(*) FROM arg_gate.gate_job WHERE event_id='{dup}'")
    print(f"  uk_event 挡掉后，dup 事件的任务数 = {cnt}（期望 1）")
    ok &= (cnt == "1")

    print("\n== 3. 可观测判据：pending 必须为 0（每条都 ack 了）==")
    stats = json.loads(call(GATE, "GET", "/api/gate/stats")[1])
    print(f"  {json.dumps(stats, ensure_ascii=False)}")
    ok &= (stats.get("pending") == 0)

    print("\n总结:", "全部通过" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
