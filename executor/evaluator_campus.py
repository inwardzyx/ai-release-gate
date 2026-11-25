"""
真评测（campus）：把 105 题盲出集的子集打给 campus-policy-rag，按 gold 条款命中率打分。

## 这一步能证明什么、不能证明什么（必须写清）

**能**：执行器能真的跑评测、把"命中率"交回门禁，门禁按发布声明的标准差判决 ——
链路两端都是真的（campus 是真服务、题集是真盲出题、gold 是真条款）。

**不能**：现在评测的是 campus **当前生效**的那一版，不是"本次发布会生效的那一版"。
因为候选版本的 prompt/参数存在 config-svc 里，而 campus 还不支持"按版本取配置"。
⇒ 要真正做到"评的是候选版本"，下一步是让被评测服务接 config-svc（按 tenant/version 拉配置）。
这一条不藏着：宁可报告里写"评测的是线上现状"，也不要假装它评的是候选版本。

## 度量口径

每道 `type=answer` 的题：调 `/chat`，看返回的 `sources[].doc` 里有没有同时包含
gold 的 [文档名, 条款名]。命中记 1，否则 0；`score = 命中数 / 题数`。
`type=refuse` 的题不参与这个分数（它度量的是"该拒的有没有拒"，是另一个口径）——
**不要混口径**，需要的话单独加一条判据。
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request

CAMPUS_URL = os.environ.get("CAMPUS_URL", "http://127.0.0.1:8028")
QUESTIONS = os.environ.get("CAMPUS_QUESTIONS",
                           r"D:\_review_rag-cs\evalset\heldout\questions.json")
NAMESPACE = os.environ.get("CAMPUS_NAMESPACE", "")   # 空 = 默认语料（校规）
LIMIT = int(os.environ.get("EVAL_LIMIT", "8"))
PER_CALL_TIMEOUT = float(os.environ.get("EVAL_CALL_TIMEOUT", "20"))


def _post(path: str, body: dict) -> dict:
    req = urllib.request.Request(
        CAMPUS_URL + path,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Auth-Roles": "public"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=PER_CALL_TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8"))


def load_questions(limit: int) -> list[dict]:
    with open(QUESTIONS, encoding="utf-8") as f:
        data = json.load(f)
    qs = [q for q in data["questions"] if q.get("type") == "answer"]
    return qs[:limit]


def run(job_id: str, prompt_key: str, version: int) -> tuple[float, str]:
    """跑评测，返回 (score, detail)。异常会抛出去，由调用方标 ERROR。"""
    qs = load_questions(LIMIT)
    if not qs:
        raise RuntimeError(f"题集里没有 answer 类型的题: {QUESTIONS}")

    hits = 0
    per_question = []
    t0 = time.time()
    for q in qs:
        body = {"question": q["question"]}
        if NAMESPACE:
            body["namespace"] = NAMESPACE
        try:
            resp = _post("/chat", body)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
            per_question.append({"id": q["id"], "hit": False, "error": f"{type(e).__name__}"})
            continue
        docs = [s.get("doc", "") for s in (resp.get("sources") or [])]
        gold_doc, gold_clause = q["gold"][0], q["gold"][1]
        hit = any(gold_doc in d and gold_clause in d for d in docs)
        hits += 1 if hit else 0
        per_question.append({"id": q["id"], "hit": hit, "knowledge_hit": resp.get("knowledge_hit")})

    score = round(hits / len(qs), 4)
    elapsed = round(time.time() - t0, 1)
    detail = (f"campus 真评测: {hits}/{len(qs)} 命中 ({score}) | {elapsed}s | "
              f"候选 {prompt_key}@{version}（评测对象是线上现状，见模块注释）")
    return score, detail


if __name__ == "__main__":
    # 手工跑一次，便于和门禁里的分数对照（不经过门禁链路）
    import sys

    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    s, d = run("manual", os.environ.get("PROMPT_KEY", "policy_qa"),
               int(os.environ.get("VERSION", "0")))
    print(d)
    print("score =", s)
