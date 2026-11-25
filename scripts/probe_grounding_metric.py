# -*- coding: utf-8 -*-
"""先量指标：答案"引自资料"的比例（grounding）能不能分开基线版与变差版？

定义：把答案切成 4 字片段（中文按字符），看有多少片段**出现在喂进去的资料原文里**。
  · 基线模板要求"只根据资料回答、没资料就说不知道" ⇒ 措辞大量来自资料 ⇒ 比例高；
  · 变差模板只说"自然流畅地回答" ⇒ 模型自由发挥 ⇒ 比例低。

为什么用这个指标而不是"gold 条款命中"：后者只看检索，prompt 改了它不动，量不出候选版本的好坏。
"""
from __future__ import annotations

import http.client
import json
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CAMPUS = 8028
KEY = "policy_qa"
BASE_VER, BAD_VER = 2, 3
QUESTIONS = [
    "宿舍里偷偷用个电热毯、电饭锅煮点东西，被查到了会挨什么处分？",
    "一个学期翘课翘到多少节，学校会直接把我开除？",
    "考试作弊被抓到会怎么处理？",
    "申请奖学金需要什么条件？",
    "转专业有什么限制？",
]
N = 4


def jcall(port, method, path, body=None, headers=None, timeout=120):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    h = {"Content-Type": "application/json", "X-Auth-Roles": "public"}
    if headers:
        h.update(headers)
    try:
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        return r.status, json.loads(r.read().decode("utf-8", "replace"))
    finally:
        c.close()


def grams(text: str, n: int = N) -> set[str]:
    t = "".join(ch for ch in text if ch.strip())
    return {t[i:i + n] for i in range(max(0, len(t) - n + 1))}


def grounding(answer: str, sources: list[dict]) -> float:
    if not answer.strip() or not sources:
        return 0.0
    pool = set()
    for s in sources:
        pool |= grams(str(s.get("text") or ""))
    a = grams(answer)
    if not a:
        return 0.0
    return round(len(a & pool) / len(a), 3)


def measure(version: int) -> list[float]:
    vals = []
    for q in QUESTIONS:
        s, r = jcall(CAMPUS, "POST", "/chat", {"question": q},
                     {"X-Config-Version": str(version)})
        assert s == 200, (s, r)
        g = grounding(r.get("answer") or "", r.get("sources") or [])
        vals.append(g)
        print(f"    v{version} {g:.3f} | {q[:16]}… | {(r.get('answer') or '')[:36]!r}")
    return vals


def main() -> int:
    print(f"基线 v{BASE_VER}：")
    base = measure(BASE_VER)
    print(f"\n变差 v{BAD_VER}：")
    bad = measure(BAD_VER)
    mb = sum(base) / len(base)
    mbad = sum(bad) / len(bad)
    print(f"\n均值：基线 {mb:.3f}  变差 {mbad:.3f}  差 {mb - mbad:+.3f}")
    print(f"逐题：{base}\n      {bad}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
