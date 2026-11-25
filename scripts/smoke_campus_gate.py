# -*- coding: utf-8 -*-
"""判据 11：真评测驱动门禁判决（EVAL_KIND=campus）。

两半：
  (a) 门槛 0.50、真评测给 4/5=0.80 ⇒ 通过，保持 CANARY；
  (b) 门槛 0.90、同一个 0.80 ⇒ 不通过 ⇒ 自动回滚。

这条的价值：证明"判决依据不是写死的常量"，而是**真的跑了评测**得到的分数。
评测时长约 20~40 秒（5 题），所以判据要等得久一点。

用法：python smoke_campus_gate.py
"""
from __future__ import annotations

import http.client
import json
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE, GATE, EXEC = 8081, 8082, 8083, 8084
KEY, ENV = "policy_qa", "prod"


def call(port, method, path, body=None, timeout=120):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json"}
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        return r.status, r.read().decode("utf-8", "replace")
    finally:
        c.close()


def jcall(port, method, path, body=None, timeout=120):
    s, b = call(port, method, path, body, timeout)
    return s, (json.loads(b) if b else None)


def wait_decision(release_id: int, timeout_s: float = 120.0):
    deadline = time.time() + timeout_s
    t0 = time.time()
    while time.time() < deadline:
        _, rel = jcall(RELEASE, "GET", f"/api/releases/{release_id}")
        if rel and rel.get("gatePassed") is not None:
            return rel, round(time.time() - t0, 1)
        time.sleep(1.0)
    return None, round(time.time() - t0, 1)


def preflight_executor() -> bool:
    """★ 前置断言：8084 上必须是 campus 版。

    没有这条时，旧进程继续服务会让判据**假绿**（本项目已踩过三次同类：
    旧 jar / 旧执行器 / 旧镜像）。判据的第一步应该是"我在测我以为的那个东西"。
    """
    try:
        s, ping = jcall(EXEC, "GET", "/ping", timeout=5)
    except Exception as e:  # noqa: BLE001
        print(f"  ✗ 执行器不可达: {e}")
        return False
    kind = (ping or {}).get("evalKind")
    print(f"  执行器 ping: {json.dumps(ping, ensure_ascii=False)}")
    if kind != "campus":
        print(f"  ✗ 8084 上跑的是 {kind!r}，不是 campus —— 判据会测到 stub，先修进程再跑")
        return False
    return True


def main() -> int:
    ok = True
    print("== 前置：确认 8084 上是 campus 版 ==")
    if not preflight_executor():
        return 2
    s, latest = jcall(CONFIG, "GET", f"/api/config/versions/latest?promptKey={KEY}")
    ver = latest["version"]
    print(f"配置版本 = {KEY}@{ver}")

    print("\n== (a) 门槛 0.50：期望真评测分 >= 0.50 ⇒ 通过、保持 CANARY ==")
    s, A = jcall(RELEASE, "POST", "/api/releases",
                 {"env": ENV, "promptKey": KEY, "version": ver, "operator": "campus"})
    s, A = jcall(RELEASE, "POST", f"/api/releases/{A['id']}/canary",
                 {"rev": A["rev"], "rolloutPct": 20, "gateThreshold": 0.50})
    relA, secsA = wait_decision(A["id"])
    print(f"  等 {secsA}s 后：passed={relA and relA.get('gatePassed')} "
          f"score={relA and relA.get('gateScore')} state={relA and relA.get('state')}")
    ok &= bool(relA and relA.get("gatePassed") is True and relA["state"] == "CANARY")

    s, jobs = jcall(GATE, "GET", f"/api/gate/jobs?releaseId={A['id']}")
    if jobs:
        print(f"  job.detail = {jobs[0].get('detail')}")

    print("\n== (b) 门槛 0.90：同一个真评测分 ⇒ 不通过 ⇒ 自动回滚 ==")
    s, B = jcall(RELEASE, "POST", "/api/releases",
                 {"env": ENV, "promptKey": KEY, "version": ver, "operator": "campus"})
    s, B = jcall(RELEASE, "POST", f"/api/releases/{B['id']}/canary",
                 {"rev": B["rev"], "rolloutPct": 10, "gateThreshold": 0.90})
    relB, secsB = wait_decision(B["id"])
    print(f"  等 {secsB}s 后：passed={relB and relB.get('gatePassed')} "
          f"score={relB and relB.get('gateScore')} state={relB and relB.get('state')}")
    ok &= bool(relB and relB.get("gatePassed") is False and relB["state"] == "ROLLED_BACK")

    s, g = jcall(RELEASE, "GET", f"/api/gray?env={ENV}&promptKey={KEY}&tenantId=tenant-42")
    print(f"  回滚后 gray -> release={g['releaseId'] if g else None}（应回到 A={A['id']}）")
    ok &= bool(s == 200 and g and g["releaseId"] == A["id"])

    print("\n总结:", "全部通过（真评测真的驱动了判决）" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
