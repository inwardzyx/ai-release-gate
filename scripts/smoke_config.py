# -*- coding: utf-8 -*-
"""config-svc 的 Day-1 冒烟 + 两条判据（幂等 / 版本号并发安全）。

用法：python smoke_config.py
"""
from __future__ import annotations

import http.client
import json
import sys
import threading
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HOST, PORT = "127.0.0.1", 8081


def call(method: str, path: str, body=None, headers=None, timeout=30):
    c = http.client.HTTPConnection(HOST, PORT, timeout=timeout)
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    t0 = time.perf_counter()
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        text = r.read().decode("utf-8", "replace")
        return r.status, text, round((time.perf_counter() - t0) * 1000), dict(
            (k.lower(), v) for k, v in r.getheaders())
    finally:
        c.close()


def main() -> int:
    key = "policy_qa"
    payload = {"model": "deepseek-chat", "temperature": 0, "retrieval": {"topK": 5}}

    print("== 1. 幂等：同一 Idempotency-Key 提交两次 ==")
    s1, b1, _, _ = call("POST", "/api/config/versions",
                        {"promptKey": key, "payload": payload, "createdBy": "zyx"},
                        {"Idempotency-Key": "idem-A"})
    s2, b2, _, _ = call("POST", "/api/config/versions",
                        {"promptKey": key, "payload": payload, "createdBy": "zyx"},
                        {"Idempotency-Key": "idem-A"})
    v1, v2 = json.loads(b1), json.loads(b2)
    print(f"  第一次 {s1} version={v1['version']} id={v1['id']}")
    print(f"  第二次 {s2} version={v2['version']} id={v2['id']}")
    ok_idem = (s1 == 201 and s2 == 200 and v1["id"] == v2["id"])
    print(f"  ⇒ 判据（201→200 且同一 id）: {'通过' if ok_idem else '失败'}")

    print("== 2. 不带幂等键 = 新版本 ==")
    s3, b3, _, _ = call("POST", "/api/config/versions",
                        {"promptKey": key, "payload": payload, "createdBy": "zyx"})
    v3 = json.loads(b3)
    print(f"  {s3} version={v3['version']}（应比上一个大 1）")

    print("== 3. 查最新 / 查列表 ==")
    s4, b4, _, _ = call("GET", f"/api/config/versions/latest?promptKey={key}")
    s5, b5, _, _ = call("GET", f"/api/config/versions?promptKey={key}&limit=5")
    print(f"  latest {s4} version={json.loads(b4)['version']}")
    print(f"  list   {s5} 共 {len(json.loads(b5))} 条，版本号 {[x['version'] for x in json.loads(b5)]}")

    print("== 4. 未知 key ⇒ 404 + ApiError ==")
    s6, b6, _, hd = call("GET", "/api/config/versions/latest?promptKey=no_such_key")
    print(f"  {s6} body={b6}")
    print(f"  traceId 回传: {hd.get('x-request-id')}")

    print("== 5. 判据：并发建 30 个版本，版本号必须唯一且连续 ==")
    results: list[tuple[int, str]] = []
    lock = threading.Lock()

    def worker(i: int) -> None:
        s, b, _, _ = call("POST", "/api/config/versions",
                          {"promptKey": "conc_key", "payload": {"i": i}, "createdBy": "load"})
        with lock:
            results.append((s, b))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(30)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = round((time.time() - t0) * 1000)

    codes: dict = {}
    versions: list[int] = []
    for s, b in results:
        codes[s] = codes.get(s, 0) + 1
        if s in (200, 201):
            versions.append(json.loads(b)["version"])
    uniq = sorted(set(versions))
    print(f"  30 并发 {elapsed}ms  状态码={codes}")
    print(f"  拿到的版本号={uniq}")
    ok_conc = (len(versions) == 30 and len(uniq) == 30 and uniq == list(range(1, 31)))
    print(f"  ⇒ 判据（30 个版本号互不重复且为 1..30）: {'通过' if ok_conc else '失败'}")

    print()
    print("总结:", "全部通过" if (ok_idem and ok_conc) else "有失败项")
    return 0 if (ok_idem and ok_conc) else 1


if __name__ == "__main__":
    raise SystemExit(main())
