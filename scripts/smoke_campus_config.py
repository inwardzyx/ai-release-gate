# -*- coding: utf-8 -*-
"""判据 12：campus 真的"按版本"取 prompt（发布门禁平台驱动被发布系统）。

两半：
  (a) **纯函数证明**（不花 LLM 调用）：同一批资料，用 v_baseline 与 v_degraded 各自渲染 prompt，
      文本必须不同，且 v_degraded 里没有"引用要求"那句；
  (b) **HTTP 证明**：`X-Config-Version` 决定这次用哪一版，响应里 `configVersion/configSource` 如实回报；
      不带这个头时走 release-svc 的生效指针（或降级为 default，且**可见**）。

用法：先设置 CONFIG_SVC_URL / RELEASE_SVC_URL 启动 campus，再跑本脚本。
"""
from __future__ import annotations

import http.client
import json
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CAMPUS, CONFIG, RELEASE = 8028, 8081, 8082
KEY = "policy_qa"
QUESTION = "宿舍里偷偷用个电热毯、电饭锅煮点东西，被查到了会挨什么处分？"

BASELINE_VERSION = 2
DEGRADED_VERSION = 3


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


def part_a_pure() -> bool:
    """同一批资料，两版模板渲染出来的 prompt 必须不同。"""
    sys.path.insert(0, r"D:\_review_rag-cs")
    import service as svc  # noqa: PLC0415

    chunks = [{"doc": "学生纪律处分管理规定.md", "clause": "第三章-第十九条（三）",
               "text": "违规使用大功率电器的，给予警告以上处分。", "cite_id": 1}]

    def tpl_of(version: int) -> dict:
        s, body = jcall(CONFIG, "GET", f"/api/config/versions/{KEY}/{version}")
        assert s == 200, (s, body)
        return body["payload"]["templates"]

    p_base = svc.RAG.build_answer_prompt(QUESTION, chunks, tpl_of(BASELINE_VERSION))
    p_deg = svc.RAG.build_answer_prompt(QUESTION, chunks, tpl_of(DEGRADED_VERSION))
    print(f"  baseline prompt 长度 {len(p_base)}，含'引用要求'：{'引用要求' in p_base}")
    print(f"  degraded prompt 长度 {len(p_deg)}，含'引用要求'：{'引用要求' in p_deg}")
    print(f"  两版文本不同：{p_base != p_deg}")
    return ("引用要求" in p_base) and ("引用要求" not in p_deg) and (p_base != p_deg)


def part_b_http() -> bool:
    ok = True
    print("\n  (b-1) 带 X-Config-Version=%d（基线）" % BASELINE_VERSION)
    s, r = jcall(CAMPUS, "POST", "/chat", {"question": QUESTION},
                 {"X-Config-Version": str(BASELINE_VERSION)})
    print(f"    status={s} configVersion={r.get('configVersion')} source={r.get('configSource')}")
    ans_base = r.get("answer") or ""
    print(f"    答案含方括号引用：{'[1]' in ans_base} | 前 60 字：{ans_base[:60]!r}")
    ok &= (s == 200 and r.get("configVersion") == BASELINE_VERSION and r.get("configSource") == "header")

    print("\n  (b-2) 带 X-Config-Version=%d（故意变差）" % DEGRADED_VERSION)
    s2, r2 = jcall(CAMPUS, "POST", "/chat", {"question": QUESTION},
                   {"X-Config-Version": str(DEGRADED_VERSION)})
    print(f"    status={s2} configVersion={r2.get('configVersion')} source={r2.get('configSource')}")
    ans_deg = r2.get("answer") or ""
    print(f"    答案含方括号引用：{'[1]' in ans_deg} | 前 60 字：{ans_deg[:60]!r}")
    ok &= (s2 == 200 and r2.get("configVersion") == DEGRADED_VERSION and r2.get("configSource") == "header")

    print("\n  (b-3) 不带头 ⇒ 走平台生效指针（或可见降级）")
    s3, r3 = jcall(CAMPUS, "POST", "/chat", {"question": QUESTION})
    print(f"    status={s3} configVersion={r3.get('configVersion')} source={r3.get('configSource')}")
    ok &= (s3 == 200 and r3.get("configSource") in ("pointer", "default"))

    print(f"\n  两版答案是否不同：{ans_base.strip() != ans_deg.strip()}")
    return ok


def main() -> int:
    s, h = jcall(CAMPUS, "GET", "/health")
    print(f"campus: status={s} chunks={h.get('chunks') if isinstance(h, dict) else '?'}")
    print("\n=== (a) 纯函数：两版模板渲染出不同 prompt ===")
    a = part_a_pure()
    print(f"  ⇒ {'通过' if a else '失败'}")
    print("\n=== (b) HTTP：X-Config-Version 决定这次用哪版 ===")
    b = part_b_http()
    print(f"\n总结: {'全部通过' if (a and b) else '有失败项'}")
    return 0 if (a and b) else 1


if __name__ == "__main__":
    raise SystemExit(main())
