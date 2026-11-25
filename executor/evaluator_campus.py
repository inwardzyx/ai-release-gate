"""
真评测（campus）：把盲出集子集打给 campus-policy-rag，按 gold 条款命中率打分。

## 这一步能证明什么、不能证明什么（必须写清）

**能**：执行器能真的跑评测、把命中率交回门禁；链路两端都是真的
（campus 是真服务、题集是真盲出、gold 是真条款）。

**不能**：现在评的是 campus **当前生效**的那一版，不是"本次发布会生效的那一版"。
候选版本的 prompt/参数在 config-svc 里，而 campus 还不支持按版本取配置。
⇒ 要评候选版本，下一步是让被评测服务接 config-svc。这条不藏着。

## ★★ 缺失 ≠ 零（2026-11-25 踩到的真事）

第一版把**任何异常都算成"没命中"**。同一个版本跑两次：18/20=0.90（70 秒）与 14/20=0.70（20 秒）。
快了一倍是因为大量请求被 campus 的限流打了 **429**（`/health` 里 `denied_rate` 涨了），
而我把"没调通"计成了"答错"。

修法三条：
1. 429/5xx/超时 **重试 + 退避**（尊重 `Retry-After`），最多 3 次；
2. 仍然失败的记为 `errored`，**不记 0 分**；
3. `errored` 超过 10% 就**抛异常**（任务变 ERROR，门禁保持"未判定"，扫描器重投）——
   宁可不判，也不许拿脏数据判决。

## 度量口径

每道 `type=answer` 的题：调 `/chat`，看 `sources[].doc` 里是否同时包含 gold 的 [文档名, 条款名]。
命中 1、未命中 0；`score = 命中 / 题数`。`type=refuse` 不参与这个分数（另一个口径，别混）。
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
NAMESPACE = os.environ.get("CAMPUS_NAMESPACE", "")   # 空 = 默认语料（校规）
LIMIT = int(os.environ.get("EVAL_LIMIT", "20"))
PER_CALL_TIMEOUT = float(os.environ.get("EVAL_CALL_TIMEOUT", "30"))
MAX_ATTEMPTS = int(os.environ.get("EVAL_RETRIES", "3"))
#: 允许的失败比例；超过就让任务报错（不判决）
MAX_ERROR_RATIO = float(os.environ.get("EVAL_MAX_ERROR_RATIO", "0.10"))


def _post(path: str, body: dict) -> dict:
    req = urllib.request.Request(
        CAMPUS_URL + path,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Auth-Roles": "public"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=PER_CALL_TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8"))


def _chat_with_retry(body: dict) -> dict:
    """带退避的重试：429 要尊重 Retry-After，5xx/超时也重试。仍失败则抛。"""
    last: Exception | None = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            return _post("/chat", body)
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


def load_questions(limit: int) -> list[dict]:
    """从盲出集里取 N 道**均匀抽样**的题（不是前 N 道）。

    ★ 为什么必须均匀抽：题集按主题排，前 N 道可能集中在某一章；门禁只考"简单的一小撮"
      分数再高也不代表整体。等步长抽样便宜、确定，且不系统性偏向某一章。
    ★ 为什么不用随机：门禁必须**可复现** —— 同一版本跑两次要得同一个分数，
      否则"这次没过"可能只是抽到了难题。
    ★ 也要如实说：N 只是 105 题的**子集**，它是"回归门禁"，不是全量评测。
    """
    with open(QUESTIONS, encoding="utf-8") as f:
        data = json.load(f)
    qs = [q for q in data["questions"] if q.get("type") == "answer"]
    if limit >= len(qs):
        return qs
    step = len(qs) / limit
    return [qs[int(i * step)] for i in range(limit)]


def run(job_id: str, prompt_key: str, version: int) -> tuple[float, str]:
    """跑评测，返回 (score, detail)。失败过多会抛异常，由调用方标 ERROR。"""
    qs = load_questions(LIMIT)
    if not qs:
        raise RuntimeError(f"题集里没有 answer 类型的题: {QUESTIONS}")

    hits, misses, errored = 0, 0, 0
    errors: list[str] = []
    t0 = time.time()

    for q in qs:
        body = {"question": q["question"]}
        if NAMESPACE:
            body["namespace"] = NAMESPACE
        try:
            resp = _chat_with_retry(body)
        except Exception as e:                     # noqa: BLE001
            errored += 1
            errors.append(f"{q['id']}:{type(e).__name__}")
            continue
        docs = [s.get("doc", "") for s in (resp.get("sources") or [])]
        gold_doc, gold_clause = q["gold"][0], q["gold"][1]
        if any(gold_doc in d and gold_clause in d for d in docs):
            hits += 1
        else:
            misses += 1

    judged = hits + misses
    if judged == 0:
        raise RuntimeError(f"全部 {errored} 题都失败，无法判决（示例错误：{errors[:3]}）")

    ratio = errored / len(qs)
    if ratio > MAX_ERROR_RATIO:
        # ★ 关键：不拿脏数据判决。抛出去 ⇒ 任务 ERROR ⇒ 门禁保持"未判定" ⇒ 扫描器重投
        raise RuntimeError(
            f"错误率 {errored}/{len(qs)}={ratio:.0%} 超过阈值 {MAX_ERROR_RATIO:.0%}，拒绝判决"
            f"（示例：{errors[:3]}）")

    score = round(hits / len(qs), 4)
    elapsed = round(time.time() - t0, 1)
    detail = (f"campus 真评测: {hits}/{len(qs)} 命中（{errored} 题调用失败不计分）"
              f" = {score} | {elapsed}s | 候选 {prompt_key}@{version}"
              f"（评测对象是线上现状，见模块注释）")
    return score, detail


if __name__ == "__main__":
    s, d = run("manual", os.environ.get("PROMPT_KEY", "policy_qa"),
               int(os.environ.get("VERSION", "0")))
    print(d)
    print("score =", s)
