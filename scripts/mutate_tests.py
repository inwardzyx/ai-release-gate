# -*- coding: utf-8 -*-
"""变异测试：把实现改坏，确认对应单测**会红**。

为什么必须做：一个永远绿的测试等于没有测试。本项目已经踩过"判据写错导致假红"，
更不能容忍"测试根本抓不到 bug"（假绿）。三次变异各打一处：
  ① 分桶：把 floorMod 换成 %（负数会出界）      → 期望 StableHashTest 红
  ② 状态机：允许 ROLLED_BACK → CANARY（复活）    → 期望 ReleaseStateTest 红
  ③ 幂等：finish 去掉 `AND passed IS NULL`        → 期望 GateJobRepositoryTest 红

每次变异后 mvn test，记录是否红，然后用 git checkout 还原。最后跑一次全量确认回到绿。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(r"D:\ai-release-gate")
MVN = r"D:\tools\apache-maven-3.9.16\bin\mvn.cmd"
ENV_JAVA = r"D:\tools\jdk-25.0.5+8"

MUTATIONS = [
    {
        "name": "① 分桶改坏：Math.floorMod → %",
        "file": ROOT / "release-svc/src/main/java/com/arg/release/gray/StableHash.java",
        "old": "return Math.floorMod(v, BUCKETS);",
        "new": "return v % BUCKETS;   // MUTANT: 负数会落到 -99..-1",
        "module": "release-svc",
        "expect_red_in": "StableHashTest",
    },
    {
        "name": "② 状态机改坏：ROLLED_BACK 允许复活",
        "file": ROOT / "release-svc/src/main/java/com/arg/release/domain/ReleaseState.java",
        "old": "            case ROLLED_BACK -> false;",
        "new": "            case ROLLED_BACK -> next == CANARY;   // MUTANT: 回滚过的发布被复活",
        "module": "release-svc",
        "expect_red_in": "ReleaseStateTest",
    },
    {
        "name": "③ 幂等改坏：finish 去掉 passed IS NULL",
        "file": ROOT / "gate-svc/src/main/java/com/arg/gate/repo/GateJobRepository.java",
        "old": "                         WHERE id=? AND passed IS NULL",
        "new": "                         WHERE id=?   -- MUTANT: 重复回调覆盖结果",
        "module": "gate-svc",
        "expect_red_in": "GateJobRepositoryTest",
    },
]


def run_test(module: str) -> tuple[bool, str]:
    env = {
        "JAVA_HOME": ENV_JAVA,
        "Path": rf"{ENV_JAVA}\bin;D:\tools\apache-maven-3.9.16\bin;" + __import__("os").environ["Path"],
        "MAVEN_OPTS": "-Xmx1g",
    }
    p = subprocess.run(
        [MVN, "-B", "-ntp", "-pl", module, "-am", "test"],
        cwd=str(ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace", env=env, timeout=900)
    out = p.stdout + p.stderr
    red = ("BUILD FAILURE" in out) or ("Tests run:" in out and "Failures: 0, Errors: 0" not in out.split("Tests run:")[-1])
    return red, out


def main() -> int:
    for m in MUTATIONS:
        f: Path = m["file"]
        original = f.read_text(encoding="utf-8")
        if m["old"] not in original:
            print(f"✗ {m['name']}: 锚点没找到，跳过（说明源码已变，需更新变异脚本）")
            return 1
        f.write_text(original.replace(m["old"], m["new"], 1), encoding="utf-8")
        try:
            red, out = run_test(m["module"])
            tail = [ln for ln in out.splitlines() if "Tests run:" in ln or "MUTANT" in ln]
            print(f"{'✓' if red else '✗'} {m['name']} → {'红（符合预期）' if red else '仍然绿（测试抓不到！）'}")
            for ln in tail[-3:]:
                print(f"     {ln.strip()[:150]}")
            if not red:
                return 1
        finally:
            f.write_text(original, encoding="utf-8")   # 还原
    print("\n还原后跑一次全量，确认回到绿：")
    red, out = run_test("release-svc")
    red2, out2 = run_test("gate-svc")
    print(f"  release-svc: {'红（异常！）' if red else '绿'}")
    print(f"  gate-svc:    {'红（异常！）' if red2 else '绿'}")
    return 0 if not (red or red2) else 1


if __name__ == "__main__":
    raise SystemExit(main())
