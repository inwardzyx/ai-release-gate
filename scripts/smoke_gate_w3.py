# -*- coding: utf-8 -*-
"""W3 判据：门禁判定 + 自动回滚 + 回调幂等。

链路：canary → outbox → Stream → gate 建任务 → 提交执行器（Python）→ 执行器回调 →
      gate 写结果 → release-svc 判定（通过=留着 CANARY / 不通过=自动回滚）→ /api/gray 看指针

判据：
  5. 门禁不通过 ⇒ **自动回滚**，/api/gray 立刻回到上一版
  6. 重复回调 3 次 ⇒ 只改变一次状态（第二次起 changed=false，发布不会被二次回滚）
  7. 门禁通过 ⇒ 发布保持 CANARY、gatePassed=true（可以人工放量）

用法：python smoke_gate_w3.py
"""
from __future__ import annotations

import http.client
import json
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE, GATE, EXEC = 8081, 8082, 8083, 8084
KEY, ENV, TENANT = "policy_qa", "prod", "tenant-42"


def call(port: int, method: str, path: str, body=None, timeout=30):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json"}
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        return r.status, r.read().decode("utf-8", "replace")
    finally:
        c.close()


def jcall(port, method, path, body=None):
    s, b = call(port, method, path, body)
    return s, (json.loads(b) if b else None)


def wait_gate_decision(release_id: int, timeout_s: float = 8.0):
    """等门禁出结果（执行器 0.4s + 回调 + 消费，给足 8 秒）。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        s, rel = jcall(RELEASE, "GET", f"/api/releases/{release_id}")
        if rel and rel.get("gatePassed") is not None:
            return rel
        time.sleep(0.3)
    return None


def gray():
    return jcall(RELEASE, "GET", f"/api/gray?env={ENV}&promptKey={KEY}&tenantId={TENANT}")


def main() -> int:
    ok = True
    print("== 0. 准备配置版本 ==")
    s, latest = jcall(CONFIG, "GET", f"/api/config/versions/latest?promptKey={KEY}")
    if s != 200:
        s, latest = jcall(CONFIG, "POST", "/api/config/versions",
                          {"promptKey": KEY, "payload": {"model": "deepseek-chat"}, "createdBy": "w3"})
    ver = latest["version"]
    print(f"  版本 = {KEY}@{ver}")

    print("\n== 1. 先让 A 生效（门槛 0.50，stub 分 0.80 ⇒ 通过）==")
    s, A = jcall(RELEASE, "POST", "/api/releases",
                 {"env": ENV, "promptKey": KEY, "version": ver, "operator": "w3"})
    s, A = jcall(RELEASE, "POST", f"/api/releases/{A['id']}/canary",
                 {"rev": A["rev"], "rolloutPct": 30, "gateThreshold": 0.50})
    print(f"  canary A id={A['id']} -> {s}")
    A_after = wait_gate_decision(A["id"])
    print(f"  门禁结果：passed={A_after and A_after.get('gatePassed')} "
          f"score={A_after and A_after.get('gateScore')} state={A_after and A_after.get('state')}")
    ok &= bool(A_after and A_after.get("gatePassed") is True and A_after["state"] == "CANARY")
    s, gA = gray()
    print(f"  gray -> release={gA['releaseId'] if gA else None} pct={gA['rolloutPct'] if gA else None}")

    print("\n== 2. 判据 5：B 门槛 0.90（stub 0.80 ⇒ 不通过）⇒ 应自动回滚到 A ==")
    s, B = jcall(RELEASE, "POST", "/api/releases",
                 {"env": ENV, "promptKey": KEY, "version": ver, "operator": "w3"})
    print(f"  建 B id={B['id']} prev={B.get('prevReleaseId')}（应等于 A 的 id={A['id']}）")
    ok &= (B.get("prevReleaseId") == A["id"])
    s, B2 = jcall(RELEASE, "POST", f"/api/releases/{B['id']}/canary",
                  {"rev": B["rev"], "rolloutPct": 10, "gateThreshold": 0.90})
    B_after = wait_gate_decision(B["id"])
    print(f"  门禁结果：passed={B_after and B_after.get('gatePassed')} "
          f"score={B_after and B_after.get('gateScore')} state={B_after and B_after.get('state')}")
    ok &= bool(B_after and B_after.get("gatePassed") is False and B_after["state"] == "ROLLED_BACK")
    s, gB = gray()
    print(f"  回滚后 gray -> release={gB['releaseId'] if gB else None}（应回到 A={A['id']}）")
    ok &= bool(s == 200 and gB and gB["releaseId"] == A["id"])

    print("\n== 3. 判据 6：同一个 job 重复回调 3 次 ⇒ 只改变一次 ==")
    s, jobs = jcall(GATE, "GET", f"/api/gate/jobs?releaseId={B['id']}")
    job = jobs[0]
    print(f"  job id={job['id']} state={job['state']} passed={job['passed']} score={job['score']}")
    changed_flags = []
    for i in range(3):
        s, r = jcall(GATE, "POST", "/api/gate/callback",
                     {"jobId": str(job["id"]), "score": 0.1, "passed": False, "detail": "manual replay"})
        changed_flags.append(r.get("changed"))
        print(f"  第 {i+1} 次回调 -> {s} changed={r.get('changed')}")
    ok &= (changed_flags[0] is False and all(f is False for f in changed_flags))
    print("  （第一次就是 False 是正确的：执行器已经判过，这次是重复回调）")
    s, relB = jcall(RELEASE, "GET", f"/api/releases/{B['id']}")
    print(f"  B 状态 {relB['state']} rev={relB['rev']}（不应被重复回滚）")
    ok &= (relB["state"] == "ROLLED_BACK")

    print("\n== 4. 判据 7：C 门槛 0.50 ⇒ 通过，保持 CANARY、gatePassed=true ==")
    s, C = jcall(RELEASE, "POST", "/api/releases",
                 {"env": ENV, "promptKey": KEY, "version": ver, "operator": "w3"})
    s, C = jcall(RELEASE, "POST", f"/api/releases/{C['id']}/canary",
                 {"rev": C["rev"], "rolloutPct": 5, "gateThreshold": 0.50})
    C_after = wait_gate_decision(C["id"])
    print(f"  门禁结果：passed={C_after and C_after.get('gatePassed')} state={C_after and C_after.get('state')}")
    ok &= bool(C_after and C_after.get("gatePassed") is True and C_after["state"] == "CANARY")

    print("\n== 5. 汇总 ==")
    s, stats = jcall(GATE, "GET", "/api/gate/stats")
    print(f"  gate stats: {json.dumps(stats, ensure_ascii=False)}")
    print("\n总结:", "全部通过" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
