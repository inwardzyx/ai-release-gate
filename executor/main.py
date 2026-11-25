"""
评测执行器（W3，最小版）。

## 它在这个系统里是什么

门禁要回答"这版 prompt 够不够好"，回答必须来自**真的跑评测**。真实评测在本项目的另一个仓库
（campus-policy-rag 的 105 题集 + McNemar），这里只做**执行器的机制**：

    gate-svc ──POST /eval/submit（带 jobId 幂等键）──▶ 执行器（本服务）
             ◀── 202 + jobId ──                     （立即返回，不阻塞调用方）
    执行器跑完 ──POST /api/gate/callback（jobId + score + passed）──▶ gate-svc

## 为什么是"提交 + 回调"而不是同步等结果

跑一次评测是分钟级。同步等会占住 gate-svc 的线程、触发各种超时与重试；
回调把"跑"和"等"分开，两侧都能独立重试。

## ★ 现在的评分是 stub（必须如实说明）

`STUB_SCORE` 默认 0.80，`--eval-kind` 也只实现了 stub。它**只用来验证管道**：
门禁阈值（gate_threshold）拿它比大小，于是同一个分数可以刻意判过或判不过。
真正的评测（接 campus 的 /chat + 105 题）是 W3+ 的下一步，接口已经定成"给 jobId 交分数"，
换实现不用动调用方。

## 幂等

`jobId` 是幂等键：同一个 jobId 重复提交，只跑一次（内存里记 `_jobs`）。
真实部署应换成 Redis/DB —— 进程重启会丢，但回调 + 重投仍在，语义是"至少一次"。
"""
from __future__ import annotations

import os
import threading
import time
import uuid
from typing import Any

import httpx
from fastapi import FastAPI, Header
from pydantic import BaseModel, Field

STUB_SCORE = float(os.environ.get("STUB_SCORE", "0.80"))
#: 评测实现：`stub`（固定分，验证链路用）| `campus`（真打 campus 的题集）
EVAL_KIND = os.environ.get("EVAL_KIND", "stub").strip().lower()
GATE_URL = os.environ.get("GATE_URL", "http://127.0.0.1:8083")
RUN_SECONDS = float(os.environ.get("EXECUTOR_RUN_SECONDS", "0.4"))

app = FastAPI(title="arg eval-executor", version="0.1.0")

_jobs: dict[str, dict[str, Any]] = {}
_lock = threading.Lock()


class SubmitRequest(BaseModel):
    jobId: str = Field(..., min_length=1)
    releaseId: int
    promptKey: str
    version: int
    threshold: float = 0.5


def _run(job_id: str, req: SubmitRequest, trace_id: str) -> None:
    """模拟跑一次评测，然后回调。失败只记状态，不重试（重试交给 gate-svc 的扫描器）。"""
    try:
        if EVAL_KIND == "campus":
            # ★ 真评测：打 campus 的 /chat 跑题集子集，按 gold 条款命中率打分。
            #   注意它评的是"线上现状"，不是候选版本 —— 详见 evaluator_campus 的模块注释。
            import evaluator_campus

            score, detail = evaluator_campus.run(job_id, req.promptKey, req.version)
        else:
            time.sleep(RUN_SECONDS)
            score = STUB_SCORE
            detail = f"stub evaluator (release={req.releaseId}, v={req.version})"
        passed = score >= req.threshold
        with _lock:
            _jobs[job_id]["state"] = "DONE"
            _jobs[job_id]["score"] = score
            _jobs[job_id]["passed"] = passed
        payload = {"jobId": job_id, "score": score, "passed": passed, "detail": detail}
        print(f"[traceId={trace_id}] callback jobId={job_id} score={score} passed={passed}", flush=True)
        with httpx.Client(timeout=3.0) as c:
            # ★ 回调也要带 traceId：否则 gate 的日志里这次回调是"无主的"
            r = c.post(f"{GATE_URL}/api/gate/callback", json=payload,
                       headers={"X-Request-Id": trace_id})
            with _lock:
                _jobs[job_id]["callbackStatus"] = r.status_code
    except Exception as e:  # noqa: BLE001
        with _lock:
            _jobs[job_id]["state"] = "ERROR"
            _jobs[job_id]["error"] = f"{type(e).__name__}: {e}"


@app.post("/eval/submit", status_code=202)
def submit(req: SubmitRequest, x_request_id: str | None = Header(default=None)) -> dict[str, Any]:
    trace_id = x_request_id or "-"
    # ★ 打一行带 traceId 的日志：判据要能在 4 个进程的日志里都找到同一个 id
    print(f"[traceId={trace_id}] submit jobId={req.jobId} release={req.releaseId} v={req.version}", flush=True)
    with _lock:
        if req.jobId in _jobs:
            # 幂等：同一个 jobId 重复提交，不重复跑
            return {"jobId": req.jobId, "state": _jobs[req.jobId]["state"], "accepted": False}
        _jobs[req.jobId] = {"jobId": req.jobId, "state": "RUNNING", "releaseId": req.releaseId,
                            "submittedAt": time.time()}
    threading.Thread(target=_run, args=(req.jobId, req, trace_id), daemon=True).start()
    return {"jobId": req.jobId, "state": "RUNNING", "accepted": True, "traceId": trace_id}


@app.get("/eval/jobs/{job_id}")
def job(job_id: str) -> dict[str, Any]:
    with _lock:
        return _jobs.get(job_id, {"jobId": job_id, "state": "UNKNOWN"})


@app.get("/ping")
def ping() -> dict[str, str]:
    """★ 带上"我跑的是哪个实现 + 代码改于何时"。

    起因：本项目已经三次踩到"端口上还是旧进程在服务"，判据于是跑在旧代码上却显示通过
    （最典型的一次：判据说"真评测通过"，detail 里却写着 stub）。
    让 /ping 报出 evalKind 与代码 mtime，判据就能先断言"我确实在和预期的那份代码说话"。
    """
    import os.path

    mtime = os.path.getmtime(__file__)
    eval_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), "evaluator_campus.py")
    eval_mtime = os.path.getmtime(eval_file) if os.path.exists(eval_file) else 0
    try:
        import evaluator_campus  # noqa: PLC0415

        limit = evaluator_campus.LIMIT
    except Exception:
        limit = -1                                   # 拿不到就写 -1（缺失），不补 0
    return {"service": "eval-executor", "status": "ok",
            "evalKind": EVAL_KIND, "stubScore": str(STUB_SCORE),
            "evalLimit": str(limit),
            "codeMtime": time.strftime("%H:%M:%S", time.localtime(mtime)),
            "evalCodeMtime": time.strftime("%H:%M:%S", time.localtime(eval_mtime))}



