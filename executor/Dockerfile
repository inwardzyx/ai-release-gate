# 评测执行器（Python）。
#
# 依赖只有三个：fastapi / uvicorn / httpx（见 requirements.txt）。
# ★ 不打生产 WSGI：这是内部服务，uvicorn 单进程足够；要扩就把 consumer 分片（见 README 的下一步）。
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 TZ=Asia/Shanghai

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py evaluator_campus.py ./

RUN addgroup --system app && adduser --system --ingroup app app && chown -R app:app /app
USER app

EXPOSE 8084
CMD ["python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8084", "--log-level", "warning"]
