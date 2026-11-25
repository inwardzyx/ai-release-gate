# AI 发布门禁平台（ai-release-gate）

> 把"改 AI（prompt / 模型 / 检索参数）"从 **改代码 + 重启 + 祈祷**，变成一次
> **可门禁、可灰度、可回滚、可审计**的发布。
> 业务是 AI，技术是后端：服务拆分、幂等、发号器、乐观锁、状态机、消息、超时预算。

**当前进度：W1 完成**（config-svc + release-svc + 灰度分流，4 条判据全绿）。

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
| gate-svc | 8083 | `arg_release`（独立表） | ⏳ W2（下一块） |
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
| 4 | 重复消息 100 次 ⇒ 门禁任务只跑一次 | ⏳ W2 |

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
