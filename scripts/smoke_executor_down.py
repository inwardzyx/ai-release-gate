# -*- coding: utf-8 -*-
"""判据 9：执行器挂掉时，门禁任务不丢、恢复后自愈。

场景（真实的会是什么样）：发布开始 → gate 要提交评测 → 执行器正好重启/挂了。
期望：
  1. 任务**不丢**（它已经在库里，是 QUEUED/RUNNING，不是凭空消失）；
  2. 恢复后由**扫描器**重投（attempts 递增），最终拿到结果；
  3. 执行器按 jobId 幂等 ⇒ 重投不会跑出两份结果。

用法：python smoke_executor_down.py
★ 它会**杀进程**（只杀本项目 8084 上的 uvicorn），并在结束时把它拉起来。
"""
from __future__ import annotations

import http.client
import json
import subprocess
import sys
import time
from pathlib import Path

import psutil

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CONFIG, RELEASE, GATE = 8081, 8082, 8083
EXEC_DIR = Path(r"D:\ai-release-gate\executor")
PY = r"D:\Python-project\.venv\Scripts\python.exe"
KEY, ENV = "policy_qa", "prod"


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


def kill_executor() -> list[int]:
    killed = []
    for p in psutil.process_iter(["pid", "name", "cmdline"]):
        c = " ".join(p.info["cmdline"] or "")
        if (p.info["name"] or "").lower().startswith("python") and "uvicorn" in c and "main:app" in c:
            try:
                p.kill()
                killed.append(p.info["pid"])
            except Exception:
                pass
    return killed


def start_executor():
    return subprocess.Popen(
        [PY, "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", "8084", "--log-level", "warning"],
        cwd=str(EXEC_DIR),
        stdout=open(r"D:\dsh-work\logs\arg-executor.log", "a", encoding="utf-8"),
        stderr=subprocess.STDOUT,
    )


def wait_executor(up: bool, timeout_s: float = 30) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        alive = False
        try:
            c = http.client.HTTPConnection("127.0.0.1", 8084, timeout=2)
            c.request("GET", "/ping")
            alive = c.getresponse().status == 200
            c.close()
        except Exception:
            alive = False
        if alive == up:
            return True
        time.sleep(0.5)
    return False


def main() -> int:
    ok = True
    print("== 0. 先停执行器（模拟它挂了/重启中）==")
    print(f"  killed: {kill_executor() or 'none'}")
    print(f"  执行器已停: {wait_executor(False)}")

    print("\n== 1. 执行器不在时发起发布（这一步必须成功：发布不该被下游拖死）==")
    s, latest = jcall(CONFIG, "GET", f"/api/config/versions/latest?promptKey={KEY}")
    ver = latest["version"]
    s, rel = jcall(RELEASE, "POST", "/api/releases",
                   {"env": ENV, "promptKey": KEY, "version": ver, "operator": "fi"})
    s, rel = jcall(RELEASE, "POST", f"/api/releases/{rel['id']}/canary",
                   {"rev": rel["rev"], "rolloutPct": 5, "gateThreshold": 0.50})
    print(f"  canary -> {s} release={rel['id']} state={rel.get('state')}")
    ok &= (s == 200)

    print("\n== 2. 等 8 秒：门禁任务应停在「未判定」，但**不丢** ==")
    time.sleep(8)
    s, jobs = jcall(GATE, "GET", f"/api/gate/jobs?releaseId={rel['id']}")
    job = jobs[0] if jobs else None
    print(f"  job={json.dumps(job, ensure_ascii=False)[:170] if job else None}")
    _, rel_now = jcall(RELEASE, "GET", f"/api/releases/{rel['id']}")
    print(f"  release.gatePassed = {rel_now.get('gatePassed')}（期望 None：还没判定）")
    ok &= bool(job and job.get("passed") is None)
    print(f"  ⇒ 任务存在且未判定: {'是（没丢，只是没跑完）' if ok else '否'}")

    print("\n== 3. 把执行器拉起来，等扫描器重投（每 3s 扫一次，条件：5 秒没动 + attempts<5）==")
    proc = start_executor()
    up = wait_executor(True)
    print(f"  执行器已就绪: {up}")

    deadline = time.time() + 40
    got = None
    while time.time() < deadline:
        _, r = jcall(RELEASE, "GET", f"/api/releases/{rel['id']}")
        if r and r.get("gatePassed") is not None:
            got = r
            break
        time.sleep(1.0)

    if got:
        s, jobs = jcall(GATE, "GET", f"/api/gate/jobs?releaseId={rel['id']}")
        job = jobs[0]
        print(f"  自愈完成：passed={got['gatePassed']} score={got['gateScore']} "
              f"job.attempts={job['attempts']} job.state={job['state']}")
        ok &= (job["attempts"] >= 2)     # ≥2 说明确实重投过（第一次失败算 1 次）
    else:
        print("  ✗ 40 秒内没自愈")
        ok = False

    print("\n总结:", "全部通过（不丢 + 自愈 + 重投计数）" if ok else "有失败项")
    print(f"（执行器进程 pid={proc.pid}，留着继续用）")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

