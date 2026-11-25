# -*- coding: utf-8 -*-
"""停掉本项目的服务进程（java -jar *-svc / python uvicorn main:app）。

★ 踩过两次的坑：用 `python -c "...关键词..."` 来找进程时，**这段脚本文本本身就含关键词**，
  于是把运行它的 pwsh/python 也匹配上，自己把自己杀了（表现为命令 exit 15 / 无输出）。
  所以：① 写成文件（本文件）；② 显式排除自己和自己的祖先；③ 只认进程名。
"""
from __future__ import annotations

import os
import sys

import psutil

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SELF = os.getpid()
SKIP = {SELF}
try:
    SKIP.update(p.pid for p in psutil.Process(SELF).parents())
except Exception:
    pass


def main() -> int:
    killed = []
    for p in psutil.process_iter(["pid", "name", "cmdline"]):
        try:
            if p.pid in SKIP:
                continue
            name = (p.info["name"] or "").lower()
            if name not in ("java.exe", "python.exe", "python3.exe", "pythonw.exe"):
                continue
            cmd = " ".join(p.info["cmdline"] or [])
            if "ai-release-gate" not in cmd:
                continue
            if "-svc\\target" not in cmd and "-svc/target" not in cmd and "uvicorn main:app" not in cmd:
                continue
            p.kill()
            killed.append(p.pid)
        except Exception:
            pass
    print("killed:", killed or "none")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

