# AI 发布门禁平台（ai-release-gate）

> 把"改 AI（prompt / 模型 / 检索参数）"从 **改代码 + 重启 + 祈祷**，变成一次
> **可门禁、可灰度、可回滚、可审计**的发布。
> 业务是 AI，技术是后端：服务拆分、幂等、发号器、乐观锁、状态机、消息、超时预算。

**当前进度：W3 闭环**（config + release + gate + Python 执行器；8 条判据全绿）。门禁判定通过就留在 CANARY、不通过**自动回滚**。

---

## 一条命令起 MySQL

```powershell
docker run -d --name arg-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=arg_root_2026 `
  -v D:\ai-release-gate\deploy\init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro mysql:8.0

# 起 config-svc（本项目自己的库是 3307，不和别的项目混）
D:\tools\jdk-25.0.5+8\bin\java.exe -jar config-svc\target\config-svc-0.1.0-SNAPSHOT.jar
```

```powershell
# 冒烟 + 两条判据（幂等、版本号并发安全）
D:\Python-project\.venv\Scripts\python.exe scripts\smoke_config.py
```

---

## 服务与端口

| 服务 | 端口 | 库 | 状态 |
|---|---|---|---|
| config-svc | 8081 | `arg_config` | ✅ W1 完成 |
| release-svc | 8082 | `arg_release` | ✅ W1 完成 |
| gate-svc | 8083 | `arg_gate` | ✅ W2/W3 完成 |
| eval-executor（Python） | 8084 | — | ✅ W3（**stub 评分**，真实 105 题待接） |
| eval-executor（Python） | — | — | ⏳ W3 |

---

## config-svc 接口（已实现）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/config/versions` | 建版本；可带 `Idempotency-Key` 头 |
| GET | `/api/config/versions?promptKey=&limit=` | 版本列表（新的在前） |
| GET | `/api/config/versions/latest?promptKey=` | 最新版本；没有就 404 + `ApiError` |
| GET | `/api/config/ping` | 只证进程活着（不查库） |

**语义**：新建返回 **201 + Location**；命中幂等键返回 **200 + 同一个版本**。
错误体统一 `{code, message, traceId}`；每个响应都带 `X-Request-Id`。

---

## 判据（每条都能证明会红）

| # | 判据 | 现状 |
|---|---|---|
| 1 | 同一 `Idempotency-Key` 提交两次 ⇒ 201 然后 200，**同一 id** | ✅ 通过 |
| 2 | 30 并发建版本 ⇒ 30 个 201，版本号 **1..30 无重复** | ✅ 通过（修前 19/30 是 500） |
| 3a | 同一 tenant 1000 次读 ⇒ 结论完全一致 | ✅ 通过 |
| 3b | 桶号与**独立实现**（Python 版 SHA-256）20 个租户逐个一致 | ✅ 通过 |
| 3c | 过期 rev 改状态 ⇒ 409 CONCURRENT_MODIFICATION | ✅ 通过 |
| 3d | 回滚 = 指针切换：B 回滚后 gray 立刻回到 A | ✅ 通过 |
| 4a | canary 一个发布 ⇒ 恰好 1 个门禁任务 | ✅ 通过 |
| 4b | 同一 eventId 手工投 **100 次** ⇒ 仍只有 **1** 个任务 | ✅ 通过 |
| 4c | 消费完 pending = 0（每条都 ack 了） | ✅ 通过 |
| 5 | 门禁不通过 ⇒ **自动回滚**，/api/gray 立刻回到上一版 | ✅ 通过（B 门槛 0.9 > stub 0.8 ⇒ 回滚到 A） |
| 6 | 同一 job 重复回调 3 次 ⇒ 状态只变一次 | ✅ 通过（changed=false × 3，B 的 rev 不变） |
| 7 | 门禁通过 ⇒ 保持 CANARY、gatePassed=true（可人工放量） | ✅ 通过 |

---

## 踩坑记录

### 2026-11-25 · 版本号分配的竞态：正确性保住了，可用性没保住

**第一版**：`SELECT COALESCE(MAX(version),0)+1` → `INSERT`，靠唯一索引 `uk_key_ver` 兜底 + 冲突重试 3 次。

**实测（30 并发建版本）**：`{201: 11, 500: 19}`，日志里是
`Duplicate entry 'conc_key-11' for key 'cfg_version.uk_key_ver'`。

**问题在哪**：唯一索引只保证"不会出现两个同号"，**不保证请求能成功**。
重试次数追不上竞争窗口 —— 30 个线程同时读同一个 max，只有一个能插入，其余全在重试里耗完。

**第二版**：加一张发号器表 `cfg_counter(prompt_key, version)`，
用 `INSERT ... ON DUPLICATE KEY UPDATE version = version + 1` 原子领号，
同 key 的请求在这一行上串行；随后同一事务里读回自己领到的号。

**关键细节**：这一步**必须 `@Transactional`** —— 否则两条 JdbcTemplate 语句可能拿到不同连接，
行锁覆盖不到后面那条 `SELECT`，等于白做。

**复测**：`{201: 30}`，版本号 1..30 无重复。

**保留了什么**：`uk_key_ver` 没删。它从"主要手段"降级为"最后防线"——
主要手段会换，最后防线不该撤。


### 2026-11-25 · 我的**判据**写错了两次（假红，不是代码错）

第 7/8 步第一次跑是红的，但代码是对的，错在期望：

1. 第一次：我在"回滚第一个发布"之后又建第二个发布，期望回滚能回到第一个 —— 但那时**指针已经被清空**（第一个发布没有 prev），所以第二个发布的 `prev` 是 null。**发布 A 已被回滚，它就不该再作为回退目标**。
2. 第二次：期望"回滚唯一生效的版本 ⇒ gray 404"，但那个版本的 `prev` 是上一轮遗留的生效版本，指针**正确地**切到了它。

**教训**：判据红了不等于代码错，也不等于判据错 —— 要先分清"是期望错了还是实现错了"。
分不清就会出现两种坏结果：把对的代码改坏（迎合错误期望），或者把错的判据删掉（消灭证据）。
这次的结论是**改期望**，理由写在脚本注释里（"回滚目标 = prevReleaseId，没有 prev 才清空"）。

**另外**：这一条连"红"本身都依赖数据库当前状态 —— 同一个脚本连跑两次，`prev` 指向会变。
所以判据要么自己造干净状态，要么显式读取真实 `prev` 再断言（现在选后者）。


### 2026-11-25 · W2：至少一次投递 + 消费幂等（判据 4 全绿）

**链路**：`canary` 在同一事务里写 `outbox_event` → `OutboxPublisher`（每 300ms 扫表）投到 Redis Stream → `gate-svc` 消费组读取 → 幂等建 `gate_job` → ack。

| 判据 | 实测 |
|---|---|
| 4a 正常路径 | 一次 canary ⇒ 该发布**恰好 1** 个门禁任务 |
| 4b 重复投递 | 同一 `eventId` **手工投 100 次** ⇒ 该 eventId 仍只有 **1** 个任务（`uk_event` 挡掉 99 次） |
| 4c 可观测 | 消费完 `pending = 0`（每条都 ack 了） |

**为什么用 outbox 而不是"写完直接 XADD"**：
直接 XADD 有两个窗口 —— 事务回滚但消息已发（幽灵事件）、事务提交但进程崩了（丢事件）。
outbox 把"写业务"和"记事件"合成一个事务，投递失败下一轮再试，语义变成**至少一次**；
重复由消费端的唯一索引去掉。**"恰好一次"不是靠消息系统，是靠业务表的唯一约束。**

**ack 顺序**：必须"处理成功 → ack"。反过来（先 ack 后处理）在处理失败时消息就永久丢了；
而"处理成功但没 ack"的最坏结果只是重复投递 —— 被唯一索引挡住。两害相权。

### 2026-11-25 · W2 踩的坑

1. **Spring Data Redis 的 API 和我想的不一样**：手动 ack 用 `StreamReadOptions.empty().noack()`，
   不是 `autoAcknowledge(false)`（该方法无参）；`StreamReadRequest` 属于监听容器那套，不适用于 `opsForStream().read(...)`。
2. **`mvn -pl gate-svc` 单独构建会缺 common**：兄弟模块要先在反应堆里装好（`-am` 或整体构建）。
3. **构建时 jar 被正在运行的服务占着** → `repackage` 报 "Unable to rename"。
   跑服务期间改代码必须先停进程（这条在 policy-desk 也踩过，属于 Windows 特有）。


### 2026-11-25 · W3：闭环（门禁判定 + 自动回滚 + 回调幂等）

**链路全通了**：

```text
canary(门槛 0.50) → outbox → Stream → gate 建任务(快照 promptKey@version)
   → 提交 Python 执行器（202，不等结果）→ 执行器跑完回调
   → gate 写结果（幂等：只在"未判定"时生效）→ release-svc 判定
        ├─ 通过  → 保持 CANARY，等人放量（自动放量不该由门禁决定）
        └─ 不通过 → **自动回滚**（指针切回 prev）
```

实测（`scripts/smoke_gate_w3.py`）：

| 判据 | 实测 |
|---|---|
| 5 自动回滚 | B 门槛 0.90 > stub 0.80 ⇒ `state=ROLLED_BACK`，`/api/gray` 回到 A |
| 6 回调幂等 | 同一 job 回调 3 次，`changed=false` × 3；B 的 rev 保持不变（没被二次回滚） |
| 7 通过路径 | C 门槛 0.50 ⇒ 保持 `CANARY`、`gatePassed=true` |

**★ 诚实标注**：执行器现在的评分是 **stub（固定 0.80）**，只验证管道；
真实评测（接 campus 的 105 题 + McNemar）是下一步，接口已定成"给 jobId 交分数"，换实现不动调用方。

**三个坑**：
1. **Redis `XGROUP CREATE` 在 stream 不存在时失败**（Spring Data Redis 的 `createGroup` 不带 MKSTREAM）⇒
   先写一条占位消息建出 stream、建完组再删掉。否则"服务先起、发布后起"这种最正常的顺序下消费组根本建不出来。
2. **MySQL 没有 `ADD COLUMN IF NOT EXISTS`**，而把 `ALTER` 写进 `schema.sql` 会在**第二次启动**时报重复列 ⇒
   新列必须直接写进 `CREATE TABLE`。
3. **执行器是独立进程**：服务全绿 ≠ 端到端通。第一次跑判据时 gate 提交到 8084 超时，日志里才有线索 ——
   "每个服务都能 ping 通"不等于"链路能跑"。

### 2026-11-25 · W4：trace 跨进程 + 压测水位 + 故障注入

**判据 8 · 一次发布能在 4 个进程的日志里找到同一个 traceId**

```text
本次 traceId = w4trace-j2er9k
  config-svc 1   release-svc 2   gate-svc 3   executor 2
```

★ 关键点：**异步链路的 trace 必须跟着事件走，不能跟着线程走**。
同步调用靠 `X-Request-Id` 头；而"发布 → 门禁"是消息触发的，消费线程上没有那个头。
所以 `traceId` 写进 outbox 的事件体，消费者取出来放回 MDC，再用 HTTP 头带给执行器，回调时再带回来。
（同时修掉两处"成功路径不打日志"：不打就永远查不到，只有出错才有日志等于没有 trace。）

**判据 9 · 压测水位（/api/gray，应用每请求都要调的热路径）**

| 并发 | 请求数 | rps | p50 | p95 | p99 |
|---|---|---|---|---|---|
| 1 | 1,516 | 190 | 4.8 | 8.6 | 12.4 |
| 4 | 8,413 | 1,052 | 3.4 | 6.1 | 8.6 |
| 16 | 16,386 | **2,048** | 6.8 | 15.2 | 22.7 |
| 32 | 16,299 | 2,037 | 12.5 | **37.3** | 54.9 |

⇒ **单进程约 2000 rps 饱和，拐点在 16 并发**（32 并发吞吐不再涨、p95 变 2.5 倍）。
这也回答了"平台会不会拖慢主链路"：满负荷下 /gray 的 p95 是 15ms 量级，冷启动单请求 5ms。

**判据 10 · 执行器挂掉：任务不丢、恢复后自愈**

| 步骤 | 实测 |
|---|---|
| 杀掉执行器后发起发布 | **200 / CANARY**（发布不被下游拖死） |
| 8 秒后看任务 | 存在、`state=RUNNING`、`attempts=2`、`passed=NULL`（**没丢，只是没跑完**） |
| 拉起执行器 | 扫描器重投 ⇒ `attempts=3`、`state=PASS`、`score=0.8` |

**坑**：我第一次跑这条时，8084 上还留着**旧代码的执行器实例**，新进程 import 失败退出，旧进程继续服务 ——
`/ping` 照样 200，判据却"看起来通过了"。**端口上的进程是哪个、跑的是哪版代码，必须核实**（
和 policy-desk 那次"镜像里是旧 jar"是同一类事故）。
