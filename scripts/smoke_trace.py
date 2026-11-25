# -*- coding: utf-8 -*-
"""判据 8：一次发布能在 **4 个进程的日志**里找到同一个 traceId。

为什么这条值得单独做：同步调用里 trace 靠请求头传；但这个项目里"发布 → 门禁"是**异步**的
（消息触发）。如果 traceId 只跟着 HTTP 头走，链到 gate 就断了。
所以它必须写进**事件体**，再由消费者放回 MDC。

判据：客户端带 `X-Request-Id: trace-w4-xxx` 发起 → 等门禁判定 →
      config / release / gate / executor 四份日志里都能 grep 到 `trace-w4-xxx`。

用法：python smoke_trace.py
"""
from __future__ import annotations

import http.client
import json
import random
import string
import subprocess
import sys
import time
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE, GATE = 8081, 8082, 8083
KEY, ENV = "policy_qa", "prod"
LOGS = Path(r"D:\dsh-work\logs")


def call(port: int, method: str, path: str, body=None, headers=None, timeout=30):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json"}
    if headers:
        h.update(headers)
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        return r.status, r.read().decode("utf-8", "replace")
    finally:
        c.close()


def find_trace(trace_id: str, file_name: str) -> int:
    """在某个日志文件里数 traceId 出现次数（只看文件内容，不依赖跑批时间点）。"""
    p = LOGS / file_name
    if not p.exists():
        return -1
    try:
        text = p.read_text(encoding="utf-8", errors="replace")
    except Exception:
        return -2
    return text.count(trace_id)


def main() -> int:
    suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
    trace_id = f"w4trace-{suffix}"
    print(f"本次 traceId = {trace_id}")

    # ★ 从"建版本"就开始带 traceId：这样 config-svc 也在同一条链上（4 个进程全串起来）
    hdr = {"X-Request-Id": trace_id}
    s, body = call(CONFIG, "POST", "/api/config/versions",
                   {"promptKey": KEY, "payload": {"model": "deepseek-chat", "trace": trace_id},
                    "createdBy": "trace"}, hdr)
    ver = json.loads(body)["version"]
    print(f"新建版本 {KEY}@{ver} -> {s}")
    s, body = call(RELEASE, "POST", "/api/releases",
                   {"env": ENV, "promptKey": KEY, "version": ver, "operator": "trace"}, hdr)
    rel = json.loads(body)
    s, body = call(RELEASE, "POST", f"/api/releases/{rel['id']}/canary",
                   {"rev": rel["rev"], "rolloutPct": 10, "gateThreshold": 0.50}, hdr)
    print(f"canary -> {s}（release={rel['id']}）")

    # 等门禁判定（执行器 0.4s + 回调 + 回写）
    deadline = time.time() + 10
    while time.time() < deadline:
        s, b = call(RELEASE, "GET", f"/api/releases/{rel['id']}")
        if json.loads(b).get("gatePassed") is not None:
            break
        time.sleep(0.3)

    time.sleep(0.5)   # 给日志刷盘一点时间
    files = {"config-svc": "arg-config.out", "release-svc": "arg-release.out",
             "gate-svc": "arg-gate.out", "executor(日志)": "arg-executor.log"}
    counts = {name: find_trace(trace_id, f) for name, f in files.items()}
    print("\n四份日志里该 traceId 出现次数：")
    ok = True
    for name, n in counts.items():
        print(f"  {name:10s} {n}")
        if n <= 0:
            ok = False
    print("\n总结:", "全部通过（4/4 进程都能串起来）" if ok else "有进程没串上")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

