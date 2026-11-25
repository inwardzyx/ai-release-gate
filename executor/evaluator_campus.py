"""
真评测（campus）：按 **grounding（答案引自资料的比例）** 打分。

## 为什么换指标（2026-11-25）

原来的指标是"gold 条款是否出现在 `sources` 里"——那衡量的是**检索**，prompt 改了它不动，
所以门禁根本判不出"候选 prompt 变差了"。换成：

    grounding = 答案的 4 字片段中，有多少片段**出现在喂进去的资料原文里**

基线模板要求"只根据资料回答、没资料就说不知道" ⇒ 措辞大量来自资料（实测 0.427）；
只说"自然流畅地回答"的变差模板 ⇒ 模型自由发挥（实测 0.207）。
**这个指标对 prompt 敏感**，门禁才谈得上"拦得住回归"。

## 候选版本怎么送到被测系统

每道题都带 `X-Config-Version: <候选版本>` 调 campus 的 `/chat`——
被测系统用**请求级覆盖**，不需要先把流量切到候选版本（那样等于"先上线再评测"）。

## 缺失 ≠ 零（这条以前踩过）

429/5xx/超时：重试 + 退避，仍失败记 `errored` 且**不记 0 分**；错误率 > 10% 直接抛异常，
让任务变 ERROR、门禁保持"未判定"、扫描器重投。**宁可不判，也不拿脏数据判决。**

## 口径

- 分值 = 有资料可引用那些题（`sources` 非空）的 grounding 均值；
- 拒答题（`sources` 为空）不参与打分——拒答由检索阈值决定，与 prompt 无关，混进来会稀释信号；
- 可判分题少于 `EVAL_MIN_JUDGED`（默认 3）就报错，不许拿 1 道题的分数下结论。
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CAMPUS_URL = os.environ.get("CAMPUS_URL", "http://127.0.0.1:8028")
QUESTIONS = os.environ.get("CAMPUS_QUESTIONS",
                           r"D:\_review_rag-cs\evalset\heldout\questions.json")
NAMESPACE = os.environ.get("CAMPUS_NAMESPACE", "")
LIMIT = int(os.environ.get("EVAL_LIMIT", "20"))
PER_CALL_TIMEOUT = float(os.environ.get("EVAL_CALL_TIMEOUT", "60"))
MAX_ATTEMPTS = int(os.environ.get("EVAL_RETRIES", "3"))
MAX_ERROR_RATIO = float(os.environ.get("EVAL_MAX_ERROR_RATIO", "0.10"))
MIN_JUDGED = int(os.environ.get("EVAL_MIN_JUDGED", "3"))
NGRAM = int(os.environ.get("EVAL_NGRAM", "4"))


def _post(path: str, body: dict, headers: dict | None = None) -> dict:
    req = urllib.request.Request(
        CAMPUS_URL + path,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Auth-Roles": "public", **(headers or {})},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=PER_CALL_TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8"))


def _chat_with_retry(body: dict, headers: dict | None = None) -> dict:
    last: Exception | None = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            return _post("/chat", body, headers)
        except urllib.error.HTTPError as e:
            last = e
            if e.code not in (429, 500, 502, 503, 504):
                raise
            wait = e.headers.get("Retry-After") if e.headers else None
            delay = float(wait) if wait and str(wait).isdigit() else float(attempt)
            time.sleep(min(max(delay, 0.5), 5.0))
        except (urllib.error.URLError, TimeoutError) as e:
            last = e
            time.sleep(min(float(attempt), 3.0))
    raise RuntimeError(f"重试 {MAX_ATTEMPTS} 次仍失败: {type(last).__name__}: {last}")


def _grams(text: str, n: int = NGRAM) -> set[str]:
    t = "".join(ch for ch in text if ch.strip())
    return {t[i:i + n] for i in range(max(0, len(t) - n + 1))}


def grounding(answer: str, sources: list[dict]) -> float | None:
    """None = 这道题没法判（没资料/没答案），不是 0。"""
    if not sources:
        return None
    a = _grams(answer)
    if not a:
        return 0.0
    pool: set[str] = set()
    for s in sources:
        pool |= _grams(str(s.get("text") or ""))
    return len(a & pool) / len(a)


def load_questions(limit: int) -> list[dict]:
    """N 道**均匀抽样**的题（等步长）：题集按主题排，取前 N 道会偏向某一章。"""
    with open(QUESTIONS, encoding="utf-8") as f:
        data = json.load(f)
    qs = [q for q in data["questions"] if q.get("type") == "answer"]
    if limit >= len(qs):
        return qs
    step = len(qs) / limit
    return [qs[int(i * step)] for i in range(limit)]


def run(job_id: str, prompt_key: str, version: int) -> tuple[float, str]:
    qs = load_questions(LIMIT)
    if not qs:
        raise RuntimeError(f"题集里没有 answer 类型的题: {QUESTIONS}")

    headers = {"X-Config-Version": str(version)} if version > 0 else {}
    scores: list[float] = []
    refused = 0
    errored = 0
    errors: list[str] = []
    t0 = time.time()

    for q in qs:
        body = {"question": q["question"]}
        if NAMESPACE:
            body["namespace"] = NAMESPACE
        try:
            resp = _chat_with_retry(body, headers)
        except Exception as e:                     # noqa: BLE001
            errored += 1
            errors.append(f"{q['id']}:{type(e).__name__}")
            continue
        g = grounding(resp.get("answer") or "", resp.get("sources") or [])
        if g is None:
            refused += 1
            continue
        scores.append(g)

    if len(scores) < MIN_JUDGED:
        raise RuntimeError(f"可判分题只有 {len(scores)} 道（<{MIN_JUDGED}），拒绝判决"
                           f"；拒答 {refused}、失败 {errored}，示例错误 {errors[:3]}")

    ratio = errored / len(qs)
    if ratio > MAX_ERROR_RATIO:
        raise RuntimeError(f"错误率 {errored}/{len(qs)}={ratio:.0%} 超过阈值 "
                           f"{MAX_ERROR_RATIO:.0%}，拒绝判决（示例：{errors[:3]}）")

    score = round(sum(scores) / len(scores), 4)
    elapsed = round(time.time() - t0, 1)
    detail = (f"campus 真评测 v{version or 'active'}: grounding {score} "
              f"（{len(scores)} 题可判 / 共 {len(qs)} 题，拒答 {refused}，失败 {errored}）"
              f" | {elapsed}s | prompt={prompt_key}")
    return score, detail


if __name__ == "__main__":
    s, d = run("manual", os.environ.get("PROMPT_KEY", "policy_qa"),
               int(os.environ.get("VERSION", "0")))
    print(d)
    print("score =", s)
