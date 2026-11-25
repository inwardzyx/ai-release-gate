# -*- coding: utf-8 -*-
"""release-svc 冒烟 + 三条判据。

判据：
  1. 灰度稳定：同一 tenant 打 1000 次，结论完全一致；且桶号与**独立实现**（本脚本用 Python 算 SHA-256）一致
     —— 后者是为了证明"稳定"不是因为同一个进程缓存，而是算法确定。
  2. 乐观锁：拿过期的 rev 去改状态 ⇒ 409 CONCURRENT_MODIFICATION（不是 500，也不是"静默成功"）。
  3. 回滚 = 指针切换：回滚后 /api/gray 立刻回到上一版；没有上一版时回到"无生效版本"（404）。

用法：python smoke_release.py
"""
from __future__ import annotations

import hashlib
import http.client
import json
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE = 8081, 8082
KEY = "policy_qa"
ENV = "prod"


def call(port: int, method: str, path: str, body=None, headers=None, timeout=30):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        text = r.read().decode("utf-8", "replace")
        return r.status, text, dict((k.lower(), v) for k, v in r.getheaders())
    finally:
        c.close()


def jget(port: int, path: str):
    s, b, _ = call(port, "GET", path)
    return s, (json.loads(b) if b else None)


def bucket_py(tenant: str) -> int:
    """独立实现（Python）—— 用来交叉验证 Java 侧不是"碰巧稳定"。"""
    d = hashlib.sha256(tenant.encode()).digest()
    v = int.from_bytes(d[0:4], "big", signed=True)
    return v % 100 if v >= 0 else (v % 100 + 100) % 100


def ensure_config_version() -> int:
    s, latest = jget(CONFIG, f"/api/config/versions/latest?promptKey={KEY}")
    if s == 200:
        return latest["version"]
    s2, body, _ = call(CONFIG, "POST", "/api/config/versions",
                       {"promptKey": KEY, "payload": {"model": "deepseek-chat", "temperature": 0},
                        "createdBy": "smoke"})
    assert s2 in (200, 201), (s2, body)
    return json.loads(body)["version"]


def main() -> int:
    ok = True
    ver = ensure_config_version()
    print(f"配置版本 baseline = {KEY}@{ver}")

    print("\n== 1. 建发布（会跨服务校验版本存在）==")
    s, body, _ = call(RELEASE, "POST", "/api/releases",
                      {"env": ENV, "promptKey": KEY, "version": ver, "operator": "zyx"})
    print(f"  {s} {body[:150]}")
    r1 = json.loads(body)
    ok &= (s == 201 and r1["state"] == "PENDING")

    print("\n== 2. 不存在的版本 ⇒ 400（fail closed，不许发布）==")
    s, body, _ = call(RELEASE, "POST", "/api/releases",
                      {"env": ENV, "promptKey": KEY, "version": 9999, "operator": "zyx"})
    print(f"  {s} {body[:150]}")
    ok &= (s == 400)

    print("\n== 3. 开始灰度 20% ==")
    s, body, _ = call(RELEASE, "POST", f"/api/releases/{r1['id']}/canary",
                      {"rev": r1["rev"], "rolloutPct": 20})
    print(f"  {s} {body[:160]}")
    r1b = json.loads(body)
    ok &= (s == 200 and r1b["state"] == "CANARY" and r1b["rolloutPct"] == 20)

    print("\n== 4. 判据 1a：同一 tenant 打 1000 次，结论必须完全一致 ==")
    tenant = "tenant-42"
    seen = set()
    t0 = time.time()
    for _ in range(1000):
        s, d = jget(RELEASE, f"/api/gray?env={ENV}&promptKey={KEY}&tenantId={tenant}")
        assert s == 200, (s, d)
        seen.add((d["version"], d["hitNew"], d["bucket"]))
    print(f"  1000 次耗时 {round((time.time()-t0)*1000)}ms，不同结论数 = {len(seen)}")
    print(f"  ⇒ 判据 1a（不同结论数应为 1）: {'通过' if len(seen) == 1 else '失败'}")
    ok &= (len(seen) == 1)

    print("\n== 5. 判据 1b：桶号与独立实现（Python 版 SHA-256）逐个比对 ==")
    mismatches = []
    for i in range(20):
        t = f"tenant-{i}"
        s, d = jget(RELEASE, f"/api/gray?env={ENV}&promptKey={KEY}&tenantId={t}")
        if d["bucket"] != bucket_py(t):
            mismatches.append((t, d["bucket"], bucket_py(t)))
    print(f"  20 个租户，不一致 {len(mismatches)} 个 {mismatches[:3]}")
    print(f"  ⇒ 判据 1b（应为 0 个不一致）: {'通过' if not mismatches else '失败'}")
    ok &= (not mismatches)

    print("\n== 6. 判据 2：拿过期 rev 改状态 ⇒ 409 ==")
    s, body, _ = call(RELEASE, "POST", f"/api/releases/{r1['id']}/canary",
                      {"rev": r1["rev"], "rolloutPct": 50})       # r1["rev"] 已经过期
    print(f"  {s} {body[:180]}")
    ok &= (s == 409)

    print("\n== 7. 判据 3：回滚 = 指针切换（A 生效 → 建 B → 回滚 B，应回到 A）==")
    s, gA = jget(RELEASE, f"/api/gray?env={ENV}&promptKey={KEY}&tenantId={tenant}")
    print(f"  A 生效时 gray: release={gA['releaseId']} version={gA['version']} pct={gA['rolloutPct']}")

    s, body, _ = call(RELEASE, "POST", "/api/releases",
                      {"env": ENV, "promptKey": KEY, "version": ver, "operator": "zyx"})
    rB = json.loads(body)
    print(f"  建 B: {s} id={rB['id']} prev={rB.get('prevReleaseId')}（应等于 A 的 id）")
    ok &= (s == 201 and rB.get("prevReleaseId") == gA["releaseId"])

    s, body, _ = call(RELEASE, "POST", f"/api/releases/{rB['id']}/canary",
                      {"rev": rB["rev"], "rolloutPct": 100})
    rBb = json.loads(body)
    s, gB = jget(RELEASE, f"/api/gray?env={ENV}&promptKey={KEY}&tenantId={tenant}")
    print(f"  B 灰度后 gray: release={gB['releaseId']} pct={gB['rolloutPct']}（应等于 B 的 id）")
    ok &= (gB["releaseId"] == rB["id"])

    s, body, _ = call(RELEASE, "POST", f"/api/releases/{rB['id']}/rollback", {"rev": rBb["rev"]})
    print(f"  回滚 B: {s} state={json.loads(body).get('state') if s == 200 else body[:80]}")
    s, gAfter = jget(RELEASE, f"/api/gray?env={ENV}&promptKey={KEY}&tenantId={tenant}")
    print(f"  回滚后 gray: release={gAfter['releaseId'] if gAfter else None}（应回到 A 的 id={gA['releaseId']}）")
    ok &= (s == 200 and gAfter is not None and gAfter["releaseId"] == gA["releaseId"])

    print("\n== 8. 回滚 A：指针应切到 A.prevReleaseId；没有 prev 时才清空（404）==")
    # /api/gray 返回的是"决策"，不带 rev —— 要改状态得先 GET 发布本身拿到最新 rev
    _, relA = jget(RELEASE, f"/api/releases/{gA['releaseId']}")
    target = relA.get("prevReleaseId")
    s, body, _ = call(RELEASE, "POST", f"/api/releases/{gA['releaseId']}/rollback",
                      {"rev": relA["rev"]})
    print(f"  回滚 A: {s} state={json.loads(body).get('state') if s == 200 else body[:80]}"
          f" | A.prev={target}")
    s, gAfter2 = jget(RELEASE, f"/api/gray?env={ENV}&promptKey={KEY}&tenantId={tenant}")
    got = (gAfter2 or {}).get("releaseId")   # 404 时 body 是错误对象，没有 releaseId
    print(f"  回滚后 gray: {s} release={got}（期望 {'404/无' if target is None else target}）")
    ok &= ((s == 404) if target is None else (s == 200 and got == target))

    print("\n总结:", "全部通过" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())




