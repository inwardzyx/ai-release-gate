# -*- coding: utf-8 -*-
"""压测 release-svc 的热路径 /api/gray（应用每次请求都要调它）。

为什么只压这一个：其余接口是低频的管理操作；只有 /gray 在线上是"每请求一次"，
它的延迟直接加在用户问答的耗时上。压它能回答"这个平台会不会拖慢主链路"。

用法：python bench_gray.py [--seconds 10] [--levels 1,4,16]
"""
from __future__ import annotations

import argparse
import http.client
import json
import statistics
import sys
import threading
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PORT = 8082
PATH = "/api/gray?env=prod&promptKey=policy_qa&tenantId=tenant-42"


def one(conn) -> tuple[int, float]:
    t0 = time.perf_counter()
    try:
        conn.request("GET", PATH)
        r = conn.getresponse()
        r.read()
        return r.status, (time.perf_counter() - t0) * 1000
    except Exception:
        return 0, (time.perf_counter() - t0) * 1000


def level(conc: int, seconds: float) -> dict:
    rows: list[tuple[int, float]] = []
    stop = threading.Event()
    lock = threading.Lock()

    def worker():
        conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=10)
        while not stop.is_set():
            code, ms = one(conn)
            with lock:
                rows.append((code, ms))

    ts = [threading.Thread(target=worker, daemon=True) for _ in range(conc)]
    t0 = time.time()
    for t in ts:
        t.start()
    time.sleep(seconds)
    stop.set()
    for t in ts:
        t.join(timeout=3)

    ok = sorted(ms for c, ms in rows if c == 200)
    if not ok:
        return {"conc": conc, "n": len(rows), "error": "no 200s",
                "codes": _codes(rows)}

    def p(q: float) -> float:
        k = max(0, min(len(ok) - 1, int(round(q / 100 * (len(ok) - 1)))))
        return round(ok[k], 1)

    return {"conc": conc, "n": len(rows), "rps": round(len(rows) / seconds, 1),
            "p50": p(50), "p95": p(95), "p99": p(99), "codes": _codes(rows)}


def _codes(rows) -> dict:
    d: dict = {}
    for c, _ in rows:
        d[c] = d.get(c, 0) + 1
    return d


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--levels", default="1,4,16")
    a = ap.parse_args()

    print(f"压测 /api/gray（单进程 release-svc，连接池 6）  每级 {a.seconds:g}s")
    print(f"{'并发':>4} {'请求':>7} {'rps':>7} {'p50':>7} {'p95':>7} {'p99':>7}  状态码")
    out = []
    for conc in [int(x) for x in a.levels.split(",")]:
        r = level(conc, a.seconds)
        out.append(r)
        if "error" in r:
            print(f"{r['conc']:4d} {r['n']:7d}  {r['error']}  {r['codes']}")
        else:
            print(f"{r['conc']:4d} {r['n']:7d} {r['rps']:7.1f} {r['p50']:7.1f} "
                  f"{r['p95']:7.1f} {r['p99']:7.1f}  {r['codes']}")
    print()
    print("JSON:", json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
