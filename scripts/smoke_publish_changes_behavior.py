# -*- coding: utf-8 -*-
"""判据 13：**发布真的改变了被发布系统的行为**（这是"toy"的正面回答）。

流程：
  1. 平台发布"基线 prompt"（版本 2）→ 灰度 100%
  2. campus 不带版本头去问 → 应当走指针，报 configSource=pointer、configVersion=2
  3. 回滚这次发布 → campus 不带版本头再问 → 应当降级（source=default），说明指针被切回去了

★ 这一步证明的不是"我写了个接口"，而是"平台的一次发布/回滚，真的改变了线上问答用的模板"。
"""
from __future__ import annotations

import http.client
import json
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE, GATE, CAMPUS = 8081, 8082, 8083, 8028
KEY, ENV = "policy_qa", "prod"
BASELINE_VERSION = 2
QUESTION = "宿舍里偷偷用个电热毯煮点东西，被查到了会挨什么处分？"


def jcall(port: int, method: str, path: str, body=None, headers=None, timeout=120):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json", "X-Auth-Roles": "public"}
    if headers:
        h.update(headers)
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        raw = r.read().decode("utf-8", "replace")
        try:
            return r.status, json.loads(raw)
        except json.JSONDecodeError:
            return r.status, raw
    finally:
        c.close()


def campus_source() -> tuple[int | None, str | None]:
    s, r = jcall(CAMPUS, "POST", "/chat", {"question": QUESTION})
    assert s == 200, (s, r)
    return r.get("configVersion"), r.get("configSource")


def wait_gate(release_id: int, timeout_s: float = 90) -> dict | None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        _, rel = jcall(RELEASE, "GET", f"/api/releases/{release_id}")
        if isinstance(rel, dict) and rel.get("gatePassed") is not None:
            return rel
        time.sleep(1.0)
    return None


def main() -> int:
    ok = True
    print(f"起点：campus 不带版本头 → {campus_source()}")

    print(f"\n== 1. 发布基线的 prompt（{KEY}@{BASELINE_VERSION}）==")
    s, rel = jcall(RELEASE, "POST", "/api/releases",
                   {"env": ENV, "promptKey": KEY, "version": BASELINE_VERSION, "operator": "integration"})
    print(f"  建发布: {s} id={rel.get('id')}")
    if s not in (200, 201):
        print(f"  失败: {rel}")
        return 1
    s, canary = jcall(RELEASE, "POST", f"/api/releases/{rel['id']}/canary",
                      {"rev": rel["rev"], "rolloutPct": 100, "gateThreshold": 0.50})
    print(f"  灰度 100%: {s} state={canary.get('state')}")

    decided = wait_gate(rel["id"])
    print(f"  门禁: passed={decided and decided.get('gatePassed')} "
          f"score={decided and decided.get('gateScore')}")
    ok &= bool(decided and decided.get("gatePassed") is True)

    print("\n== 2. 出版后，campus 不带版本头再问（应走指针）==")
    ver, source = campus_source()
    print(f"  configVersion={ver} configSource={source}（期望 pointer / {BASELINE_VERSION}）")
    ok &= (source == "pointer" and ver == BASELINE_VERSION)

    print("\n== 3. 回滚这次发布，再看 campus ==")
    _, latest = jcall(RELEASE, "GET", f"/api/releases/{rel['id']}")
    s, rb = jcall(RELEASE, "POST", f"/api/releases/{rel['id']}/rollback", {"rev": latest["rev"]})
    print(f"  回滚: {s} state={rb.get('state') if isinstance(rb, dict) else rb}")
    time.sleep(6)      # 指针缓存 5 秒
    ver2, source2 = campus_source()
    print(f"  configVersion={ver2} configSource={source2}（期望不再是 pointer/2）")
    ok &= not (source2 == "pointer" and ver2 == BASELINE_VERSION)

    print("\n总结:", "全部通过（发布/回滚真的改变了被发布系统的行为）" if ok else "有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
