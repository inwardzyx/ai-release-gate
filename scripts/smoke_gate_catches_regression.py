# -*- coding: utf-8 -*-
"""判据 14：**门禁拦下真实回归**（候选 prompt 变差 ⇒ 自动回滚 ⇒ 线上继续用旧版）。

这是"平台不是 toy"的最后一段：前面已经证明"发布能改变行为"，
这里证明"变差的发布会被自动拦回去"，而且**线上用户没受影响**。

流程：
  1. 发布 v_baseline（grounding ≈ 0.50）→ 门槛 0.40 → 通过，指针指向它
  2. 发布 v_degraded（grounding ≈ 0.26）→ 同一门槛 → 不通过 → 自动回滚
  3. 核对：发布记录 ROLLED_BACK；`/api/gray` 与 `/api/releases/active` 仍指向 baseline；
     campus 不带版本头去问，仍然报 source=pointer / version=baseline

★ 前提：执行器以 EVAL_KIND=campus 运行（不是 stub），且能访问 campus。
"""
from __future__ import annotations

import http.client
import json
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE, GATE, CAMPUS, EXEC = 8081, 8082, 8083, 8028, 8084
KEY, ENV = "policy_qa", "prod"
BASELINE_VERSION, DEGRADED_VERSION = 2, 3
THRESHOLD = 0.40
QUESTION = "宿舍里偷偷用个电热毯煮点东西，被查到了会挨什么处分？"


def jcall(port, method, path, body=None, headers=None, timeout=180):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json", "X-Auth-Roles": "public"}
    if headers:
        h.update(headers)
    try:
        c.request(method, json.dumps(body) if body is not None else None, path, encode_chunked=False) \
            if False else c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        raw = r.read().decode("utf-8", "replace")
        try:
            return r.status, json.loads(raw)
        except json.JSONDecodeError:
            return r.status, raw
    finally:
        c.close()


def wait_decided(release_id: int, timeout_s: float = 240) -> dict | None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        _, rel = jcall(RELEASE, "GET", f"/api/releases/{release_id}")
        if isinstance(rel, dict) and rel.get("gatePassed") is not None:
            return rel
        time.sleep(2.0)
    return None


def publish(version: int) -> dict:
    s, rel = jcall(RELEASE, "POST", "/api/releases",
                   {"env": ENV, "promptKey": KEY, "version": version, "operator": "regression"})
    assert s in (200, 201), (s, rel)
    s, c = jcall(RELEASE, "POST", f"/api/releases/{rel['id']}/canary",
                 {"rev": rel["rev"], "rolloutPct": 100, "gateThreshold": THRESHOLD})
    assert s == 200, (s, c)
    return rel


def main() -> int:
    ok = True
    s, ping = jcall(EXEC, "GET", "/ping")
    print(f"执行器: {json.dumps(ping, ensure_ascii=False)}")
    if ping.get("evalKind") != "campus":
        print("  ✗ 执行器不是 campus 版，这一条会测到 stub —— 先改 EVAL_KIND 再跑")
        return 2

    print(f"\n== 1. 发布基线 v{BASELINE_VERSION}（门槛 {THRESHOLD}）==")
    rel_a = publish(BASELINE_VERSION)
    dec_a = wait_decided(rel_a["id"])
    print(f"  门禁: passed={dec_a and dec_a.get('gatePassed')} score={dec_a and dec_a.get('gateScore')} "
          f"state={dec_a and dec_a.get('state')}")
    ok &= bool(dec_a and dec_a.get("gatePassed") is True)

    print(f"\n== 2. 发布故意变差的 v{DEGRADED_VERSION}（同一门槛）==")
    rel_b = publish(DEGRADED_VERSION)
    dec_b = wait_decided(rel_b["id"])
    print(f"  门禁: passed={dec_b and dec_b.get('gatePassed')} score={dec_b and dec_b.get('gateScore')} "
          f"state={dec_b and dec_b.get('state')}")
    ok &= bool(dec_b and dec_b.get("gatePassed") is False and dec_b["state"] == "ROLLED_BACK")

    if dec_b:
        _, jobs = jcall(GATE, "GET", f"/api/gate/jobs?releaseId={rel_b['id']}")
        if jobs:
            print(f"  job.detail = {jobs[0].get('detail')}")

    print("\n== 3. 线上没受影响：指针仍是基线 ==")
    _, active = jcall(RELEASE, "GET", f"/api/releases/active?env={ENV}&promptKey={KEY}")
    print(f"  /active: version={active.get('version')} release={active.get('id')}（期望 {BASELINE_VERSION}）")
    ok &= (active.get("version") == BASELINE_VERSION)

    s, g = jcall(RELEASE, "GET", f"/api/gray?env={ENV}&promptKey={KEY}&tenantId=tenant-42")
    print(f"  /gray: version={g.get('version')} release={g.get('releaseId')}（期望 {BASELINE_VERSION}）")
    ok &= (g.get("version") == BASELINE_VERSION)

    s, ans = jcall(CAMPUS, "POST", "/chat", {"question": QUESTION})
    print(f"  campus 不带版本头: configVersion={ans.get('configVersion')} source={ans.get('configSource')}"
          f"（期望 {BASELINE_VERSION} / pointer）")
    ok &= (ans.get("configVersion") == BASELINE_VERSION and ans.get("configSource") == "pointer")

    print("\n总结:", "全部通过（变差的候选被门禁拦下并回滚，线上未受影响）" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
