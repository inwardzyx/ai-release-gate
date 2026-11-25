# -*- coding: utf-8 -*-
"""停掉本项目的服务进程：3 个 Java 服务 + Python 执行器。

## 为什么这里要写这么细（三个真事故）

1. **用 `python -c "...关键词..."` 找进程会把自己也算进去**（脚本文本里就含关键词），
   表现为命令 exit 15 / 无输出。所以：① 写成文件；② 排除自己与祖先；③ 只认进程名。
2. **按路径匹配漏掉执行器**：Java 服务的命令行里有 `ai-release-gate`，但执行器只有
   `-m uvicorn main:app`（项目路径只在 cwd 里）⇒ 第一版从来没停掉执行器，
   于是 8084 上一直由**旧进程提供旧代码**，判据"通过"其实跑的是 stub。
3. **补丁没生效却照样写回文件**（PowerShell 的 `.Replace()` 未命中不报错）⇒
   修好的规则看起来在、其实没在。所以本文件不再做片段替换，直接整文件重写。

用法：python scripts/stop_services.py [--dry-run]
"""
from __future__ import annotations

import os
import sys

import psutil

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SELF = os.getpid()
SKIP_PIDS = {SELF}
try:
    SKIP_PIDS.update(p.pid for p in psutil.Process(SELF).parents())
except Exception:
    pass

PY_NAMES = {"python.exe", "python3.exe", "pythonw.exe"}
JAVA_NAMES = {"java.exe", "javaw.exe"}


def classify(pid: int, name: str, cmd: str) -> str:
    """返回 'java-svc' / 'executor' / 'skip:<原因>'。"""
    if pid in SKIP_PIDS:
        return "skip:自己或祖先"
    low = name.lower()
    if low in JAVA_NAMES:
        if "ai-release-gate" in cmd and ("-svc\\target" in cmd or "-svc/target" in cmd):
            return "java-svc"
        return "skip:不是本项目 jar"
    if low in PY_NAMES:
        # ★ 执行器只按"uvicorn + main:app"认：它的命令行里没有项目路径
        if "uvicorn" in cmd and "main:app" in cmd:
            return "executor"
        return "skip:不是执行器"
    return "skip:不是 java/python"


def main() -> int:
    dry = "--dry-run" in sys.argv
    killed, skipped = [], []
    for p in psutil.process_iter(["pid", "name", "cmdline"]):
        try:
            cmd = " ".join(p.info["cmdline"] or "")
            kind = classify(p.pid, p.info["name"] or "", cmd)
            if kind.startswith("skip"):
                # 只报告"含相关关键词但不是本项目"的，避免刷屏
                if "ai-release-gate" in cmd or "main:app" in cmd:
                    skipped.append((p.pid, kind))
                continue
            if not dry:
                p.kill()
            killed.append((p.pid, kind))
        except Exception as e:                       # noqa: BLE001
            skipped.append((p.info.get("pid"), f"kill 失败: {e}"))

    print(f"killed ({len(killed)}): {killed}")
    if skipped:
        print(f"skipped ({len(skipped)}): {skipped}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
