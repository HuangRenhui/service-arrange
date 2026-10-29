# REST 接口说明

本文档列出 `web` 模块暴露的全部 HTTP 接口。

> 代码依据：`backend/web/src/main/java/com/hrh/servicearrange/controller/*`（已逐行核对）。
> 部署信息：端口 `8082`，上下文路径 `/servicearrange`（见 `application.yml`）。

---

## 📋 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档名称 | REST接口说明 |
| 文档版本 | v2.0 |
| 最后更新 | 2026-10-28 |
| 状态 | 现行 |
| 接口总数 | 18 个 |
| 相关代码 | `backend/web/**/controller/*Controller.java` · `filter/TraceFilter.java` · `vo/ApiResponse.java` |

---

## 目录

- [§0 部署上下文](#§0-部署上下文)
- [§1 接口总览](#§1-接口总览)
- [§2 运行实例 / 查询结果](#§2-运行实例--查询结果)
- [§3 流程模型（Plan）管理](#§3-流程模型plan管理)
- [§4 实例查询与干预](#§4-实例查询与干预)
- [§5 实例日志](#§5-实例日志)
- [§6 健康检查](#§6-健康检查)
- [§7 保存算子注册](#§7-保存算子注册)
- [§8 批量删除算子注册](#§8-批量删除算子注册)
- [§9 查询算子注册](#§9-查询算子注册)
- [§10 错误处理](#§10-错误处理)
- [§11 前端对接清单](#§11-前端对接清单)
- [§12 统一返回结构](#§12-统一返回结构)

---

## §0 部署上下文

| 项 | 值 | 来源 |
| --- | --- | --- |
| 端口 | `8082` | `server.port` |
| 上下文路径 | `/servicearrange` | `server.servlet.context-path` |
| 应用名 | `servicearrange` | `spring.application.name` |

**完整基础地址**：`http://localhost:8082/servicearrange`

> 前端「接口设置」中应填 `http://localhost:8082/servicearrange`（含上下文路径）。

---

## §1 接口总览

| # | 方法 | 完整路径 | 用途 | Controller |
| --- | --- | --- | --- | --- |
| 1 | POST | `/servicearrange/inst/run/or/getResult/{planId}` | 运行实例 / 查询实例结果 | `InstController` |
| 2 | GET | `/servicearrange/plan/list` | 流程模型列表 | `PlanController` |
| 3 | GET | `/servicearrange/plan/findById` | 流程模型详情 | `PlanController` |
| 4 | POST | `/servicearrange/plan/save` | 保存流程模型（新建 / 更新）| `PlanController` |
| 5 | POST | `/servicearrange/plan/publish` | 发布流程模型 | `PlanController` |
| 6 | POST | `/servicearrange/plan/disable` | 停用流程模型 | `PlanController` |
| 7 | POST | `/servicearrange/plan/delete` | 批量删除流程模型 | `PlanController` |
| 8 | GET | `/servicearrange/inst/list` | 实例列表（按 `planId` / `state` 过滤）| `InstQueryController` |
| 9 | GET | `/servicearrange/inst/detail` | 实例详情（实例 + 任务 + 日志）| `InstQueryController` |
| 10 | POST | `/servicearrange/inst/suspend` | 挂起实例 | `InstQueryController` |
| 11 | POST | `/servicearrange/inst/resume` | 恢复挂起实例 | `InstQueryController` |
| 12 | POST | `/servicearrange/inst/cancel` | 取消实例 | `InstQueryController` |
| 13 | GET | `/servicearrange/instlog/list` | 实例级 / 算子级日志 | `InstLogController` |
| 14 | GET | `/servicearrange/health` | 健康检查（含 MongoDB 探活）| `HealthController` |
| 15 | POST | `/servicearrange/registerOperator/save` | 保存算子注册信息 | `RegisterInfoController` |
| 16 | POST | `/servicearrange/registerOperator/delete` | 批量删除算子注册信息 | `RegisterInfoController` |
| 17 | GET/POST | `/servicearrange/registerOperator/findById` | 按 id 查询算子注册信息 | `RegisterInfoController` |
| 18 | GET/POST | `/servicearrange/registerOperator/list` | 算子注册列表 | `RegisterInfoController` |

> 除 `#1`（运行实例，返回业务数据本身）与 `#14`~`#16`（返回裸值）外，其余接口统一返回 `{code,msg,data}`，见 §12。

### 1.1 横切能力（`filter/TraceFilter.java`）

所有接口都经过链路过滤器：

| 项 | 行为 |
| --- | --- |
| 入参 | 请求头 `X-Trace-Id`（大小写不敏感，`x-trace-id` 亦可）|
| 缺省 | 未传时由服务端生成 32 位 UUID（去横线）|
| 出参 | 响应头回写 `X-Trace-Id` |
| 上下文 | 写入 `TraceContext`（ThreadLocal）与 `MDC.traceId`，日志可直接按 traceId 检索 |

---

## §2 运行实例 / 查询结果

### 2.1 基本信息

| 项 | 值 |
| --- | --- |
| 路径 | `/inst/run/or/getResult/{planId}` |
| 方法 | `POST` |
| 内容类型 | `multipart/form-data`（推荐）或无 body 的普通 POST |
| 路径参数 | `planId`：流程（模型）id |
| 响应 | `{code,msg,data:{id,state,traceId,planVersion,idempotentHit}}`（`get_result` 成功时除外）|

> ⚠️ **重要**：接口实际从请求体中读取的是**保留字段 + 业务入参**，且业务入参的**类型还原**依赖开始节点的 `inputsJsonSchema`。详见 2.2。

### 2.2 参数说明

#### 2.2.1 保留字段（由框架识别，不进入流程入参逻辑）

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `servea_optType` | `String` | 否 | `"run"` | `run` = 运行实例；`get_result` = 查询结果。**注意前缀 `servea_`** |
| `servea_sync` | `Boolean` | 否 | `true` | 是否同步。**注意默认值为 `true`** |
| `servea_instName` | `String` | 否 | — | 实例名称（写入 `InstRunParamsVo.instName`）|
| `servea_instId` | `String` | `get_result` 时**必填** | — | 目标实例 id；缺省抛 400「get_result 需要 servea_instId」|
| `servea_idempotencyKey` | `String` | 否 | — | 幂等键；与请求头 `X-Idempotency-Key` 等价（**请求头优先**）|

#### 2.2.2 业务入参

除保留字段外，**其余字段均为流程入参**，键名应与开始节点 `inputsJsonSchema.properties` 的字段名一致。

类型还原规则（`InstController.putTypedParam`，依据 `DslParser.getDslStartParamsTypeMap`）：

| Schema 声明的 type | 还原为 |
| --- | --- |
| `text` / `String` | 字符串 |
| `array` / `jsonArrStr` | JSON 数组（空串 → `null`）|
| `number` | `Long`（空串 → `0`）|
| `boolean` | `Boolean`（空串 → `false`）|
| `object` / `jsonObjStr` | JSON 对象（空串 → `null`）|
| 其他 / 未声明 | 原样字符串 |

> 类型转换失败抛 400：`参数 {name} 无法按类型 {type} 转换：...`。
> 依赖 `JsonSchemaUtil.jsonConfig`（`setIgnoreNullValue(false)`），保证 `null` 值不被丢弃。

#### 2.2.3 请求头

`InstController` 会**采集全部请求头**存入 `Inst.headerParams`（同名头用逗号合并），供 `#header_$Xxx` 表达式取值。无需在 body 中重复传递。

其中两个头有特殊语义：

| 请求头 | 说明 |
| --- | --- |
| `X-Idempotency-Key` | 幂等键，优先于 `servea_idempotencyKey` |
| `X-Caller-App` | 调用方标识，写入 `Inst.callerApp`（用于按来源排查）|

#### 2.2.4 文件上传

`multipart/form-data` 中的文件字段（`multiFileMap`）会**落盘到本地**，并回填到实例入参：

| 项 | 说明 |
| --- | --- |
| 存储实现 | `LocalFileStorage`（`backend/engine/.../files/LocalFileStorage.java`）|
| 存储目录 | `{servea.file.root}/{instId}/{原始文件名}`，同名自动追加 `-1`、`-2` |
| 参数值 | 实例入参中该字段的值改为 `local://{instId}/{存储文件名}`，供下游 `fileUrl` / 文件参数引用 |
| 大小上限 | `servea.file.max-size`（默认 100MB），同时受 `spring.servlet.multipart.max-file-size` 约束 |
| 保存时机 | 实例落库（拿到 `instId`）之后保存，因此文件目录天然与实例一一对应 |
| 保存失败 | 抛 500，实例不会带着占位值继续运行 |

### 2.3 DSL 来源与发布门禁

DSL 加载顺序（`InstController.loadDsl`）：

1. 按 `planId` 查 `SERVEA_PLAN`，命中则用其 `dsl` 字段：
   - `status = DISABLED` → 400「流程已停用，无法运行」；
   - `task.orchestration.require-published = true` 且 `status != PUBLISHED` → 400「仅允许运行已发布流程，当前状态=…」；
   - 命中时同步把 `Plan.version` / `Plan.env` 写入实例的 `planVersion` / `env`。
2. 未命中 Plan 时按名称回退 classpath / 工作目录文件：`{planId}`、`{planId}.json`、`hrh_{planId}.json`、`hrh_http.json`。
3. 全部未命中 → 400「找不到流程 DSL，planId=…」。

> `require-published = true` 时**不会回退**到 classpath 文件，保证生产环境只跑已发布版本。配置见 [配置说明](12-配置说明.md) §3.3.1。

### 2.4 幂等

| 项 | 说明 |
| --- | --- |
| 生效条件 | `planId + idempotencyKey` 命中已有实例，且该实例状态**不是** `FAIL` / `CANCEL` |
| 命中行为 | 不再新建实例，直接返回已有实例信息，`data.idempotentHit = true` |
| 不生效场景 | `servea_optType=get_result`（查询不参与幂等）；未传幂等键 |
| 存储支撑 | `SERVEA_INST` 上的复合索引 `uk_plan_idempotency = {planId:1, idempotencyKey:1}`（`unique` + `sparse`），并发提交由数据库唯一约束兜底，冲突后回读已存在实例 |

### 2.4.1 流程运行准入（同一流程只跑一个）

幂等键只能防“同一个键重复提交”；不传键或每次用新键时，同一流程仍会被并发运行。为此 `/inst/run` 增加 **流程级准入锁**：

| 项 | 说明 |
| --- | --- |
| 开关 | `task.orchestration.single-running-per-plan`（默认 `true`）|
| 机制 | ZooKeeper `EPHEMERAL` 节点 `/PLAN_RUNNING_{planId}`，数据为持有者 `instId`，`create` 原子抢占 |
| 抢占失败 | **删除本次新建的实例**并返回 HTTP 400：`{"code":400,"msg":"流程正在运行中（实例 xxx），请等待结束或取消后再运行"}` |
| 释放时机 | `POST /inst/cancel`、`POST /inst/delete` 显式释放；属主实例已 `SUCCESS` / `FAIL` 时，下一次运行会识别为陈旧锁并自动清理；ZooKeeper 会话结束自动消失 |
| ZK 不可用 | 回退按库内状态校验（同 `planId` 下是否存在 `WAITE` / `RUNNING` 实例）；库内也没有则放行（fail-open），并打 WARN |
| 实现 | `backend/engine/.../lock/PlanRunLock.java` |

> 与 ZK 实例锁 `/INST_LOCK_{instId}` 的区别：`/INST_LOCK_` 解决**同一实例内多节点串行**（消费端抢占），
> `/PLAN_RUNNING_` 解决**同一流程多实例并发**（入口准入）。

### 2.5 请求示例

**运行业例**

```http
POST /servicearrange/inst/run/or/getResult/PLAN_2026_001 HTTP/1.1
Host: localhost:8082
Content-Type: multipart/form-data; boundary=----X
X-AToken: abc123
X-Idempotency-Key: order-2026-0001
X-Caller-App: order-center

------X
Content-Disposition: form-data; name="servea_optType"

run
------X
Content-Disposition: form-data; name="servea_sync"

true
------X
Content-Disposition: form-data; name="name"

张三
------X
Content-Disposition: form-data; name="age"

28
------X--
```

**查询结果**

```http
POST /servicearrange/inst/run/or/getResult/PLAN_2026_001 HTTP/1.1
Host: localhost:8082
Content-Type: multipart/form-data; boundary=----X

------X
Content-Disposition: form-data; name="servea_optType"

get_result
------X
Content-Disposition: form-data; name="servea_instId"

6650f3a1c2b3d4e5f6a7b8c9
------X--
```

### 2.6 响应示例

**运行 / 异步触发**（统一包装）：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "id": "6650f3a1c2b3d4e5f6a7b8c9",
    "state": "RUNNING",
    "traceId": "8f2c1e4a9b0d47f5a1c3e7d2b6f80915",
    "planVersion": 3,
    "idempotentHit": false
  }
}
```

**查询结果成功**：直接返回实例 `outputs`，**不包装**。

| `outputs.contentType` | 返回 |
| --- | --- |
| `application/json` | 解析后的 JSON 对象 / 数组；非合法 JSON 则原样字符串 |
| `text/plain` | 字符串 |
| 其他且 `value` 以 `local://` 开头 | 流式下载本地文件：`Content-Disposition: attachment`、`Content-Length`、按扩展名推断 `Content-Type`；文件不存在 → 400 |
| 其他且 `value` 不是 `local://` | 400「不支持的文件地址（仅支持 local://）」 |
| 无 `outputs` | 实例 id 字符串 |

**查询结果失败**：实例不存在或未成功时返回 HTTP 400：

```json
{ "code": 400, "msg": "实例还未运行成功，无输出信息！", "data": null }
```

### 2.7 行为说明

| `servea_optType` | 行为 |
| --- | --- |
| `run` | 解析 DSL → 幂等查重 → 生成 `Inst` 落库（`WAITE`）→ 抢占流程准入锁（被占用则删实例并 400）→ 文件落盘 → 校验开始节点 → 置 `RUNNING` → `StartTask.convert` → `taskDao.save` → `taskProductor.sendTaskResult` 触发执行 |
| `get_result` | 按 `servea_instId` 查实例：不存在 → 400；状态非 `SUCCESS` → 400；`SUCCESS` → 返回 `outputs` |

> `servea_sync` 仅写入 `Inst.sync` 字段，Controller **未实现等待轮询**，请求立即返回（同步语义由调用方轮询 `get_result` 或 `inst/detail` 实现）。

### 2.8 已知限制

| 项 | 说明 |
| --- | --- |
| 同步语义 | `servea_sync` 不影响响应时机，始终立即返回 |
| 文件存储 | 落本地磁盘（`servea.file.root`），不再依赖 `filemgr://` 远程文件服务；历史数据中的 `filemgr://` 地址无法解析，下载会返回 400 |
| 文件生命周期 | 文件与实例目录绑定，删除实例时整目录级联删除（见 §4.6）|
| 返回结构 | `get_result` 成功时返回业务数据本身，与其它接口的 `{code,msg,data}` 不一致（见 §12）|

---

## §3 流程模型（Plan）管理

### 3.1 接口一览

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/plan/list` | — | 全量列表 |
| GET | `/plan/findById` | `id`（query）| 详情；未命中返回 `data: null` |
| POST | `/plan/save` | body `Plan` | 新建 / 更新 |
| POST | `/plan/publish` | `id`（query）| 发布 |
| POST | `/plan/disable` | `id`（query）| 停用 |
| POST | `/plan/delete` | body `List<String>` | 批量删除 |

### 3.2 模型字段（`Plan`，集合 `SERVEA_PLAN`）

| 字段 | 类型 | `@Field` | 说明 |
| --- | --- | --- | --- |
| `id` | `String` | `id` | 主键；为空即新建（继承自 `BaseEntity`）|
| `name` | `String` | `name` | 模型名称 |
| `dsl` | `String` | `dsl` | DSL JSON 字符串 |
| `remark` | `String` | `remark` | 备注 |
| `version` | `Integer` | `version` | 版本号，默认 `1` |
| `status` | `String` | `status` | `DRAFT` / `PUBLISHED` / `DISABLED`，默认 `DRAFT` |
| `env` | `String` | `env` | 环境标识（写入实例 `env`）|
| `owner` | `String` | `owner` | 负责人 |
| `defaultTimeoutMs` | `Long` | `defaultTimeoutMs` | 默认任务超时（节点 `RetryRules.timeoutMs` 优先）|
| `createDate` / `modifyDate` | `Date` | — | 创建 / 修改时间 |

### 3.3 保存语义（`POST /plan/save`）

| 场景 | 行为 |
| --- | --- |
| `id` 为空 | 新建，`createDate = 当前时间` |
| `id` 非空且已存在 | 更新；`createDate` 沿用旧值；**请求未显式传 `version` 时自增 1** |
| `status` 为空 | 置为 `DRAFT` |
| `modifyDate` | 每次保存都刷新为当前时间 |

### 3.4 发布 / 停用 / 删除

| 接口 | 行为 | 异常 |
| --- | --- | --- |
| `/plan/publish` | 校验 `dsl` 非空 → `status = PUBLISHED` → `version + 1` → 落库 | `dsl` 为空 → 400「空 DSL 不能发布」；id 不存在 → 400「流程不存在」|
| `/plan/disable` | `status = DISABLED` → 落库 | id 不存在 → 400「流程不存在」|
| `/plan/delete` | 按传入 id 列表逐个删除，返回 `data: "success"` | id 列表为 `null` 时空跑 |

> 运行门禁：`DISABLED` 的模型任何情况下都不能运行；`require-published = true` 时只有 `PUBLISHED` 可运行（见 §2.3）。

### 3.5 请求响应示例

**保存**

```http
POST /servicearrange/plan/save HTTP/1.1
Content-Type: application/json

{
  "id": "PLAN_2026_001",
  "name": "订单编排",
  "remark": "下单后扣减库存",
  "dsl": "{\"cells\":[],\"groups\":[]}"
}
```

```json
{
  "code": 200,
  "msg": "success",
  "data": { "id": "PLAN_2026_001", "name": "订单编排", "version": 2, "status": "DRAFT" }
}
```

**发布**

```http
POST /servicearrange/plan/publish?id=PLAN_2026_001 HTTP/1.1
```

```json
{ "code": 200, "msg": "success", "data": { "id": "PLAN_2026_001", "version": 3, "status": "PUBLISHED" } }
```

---

## §4 实例查询与干预

| 方法 | 路径 | 参数 | 说明 |
| --- | --- | --- | --- |
| GET | `/inst/list` | `planId`（可选）、`state`（可选）| 实例列表；先按 `planId` 过滤，再按 `state` 过滤 |
| GET | `/inst/detail` | `id`（query，必填）| 实例详情 |
| POST | `/inst/suspend` | `id`（query，必填）| 挂起实例（不打断在途节点）|
| POST | `/inst/resume` | `id`（query，必填）| 恢复挂起实例 |
| POST | `/inst/cancel` | `id`（query，必填）| 取消实例（只停调度）|
| POST | `/inst/delete` | body `List<String>`、`force`（query，可选）| 删除实例并级联清理任务、日志、本地文件 |

### 4.1 `/inst/list`

- 两个参数都可省：都不传 = 全量列表。
- `state` 取值：`WAITE` / `RUNNING` / `SUCCESS` / `FAIL` / `SUSPEND` / `RESUME` / `CANCEL`。

### 4.2 `/inst/detail`

返回聚合结构：

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "inst": { "id": "…", "state": "SUCCESS", "traceId": "…", "planVersion": 3 },
    "tasks": [ { "nodeId": "n1", "state": "SUCCESS", "loopTimes": "0" } ],
    "logs": [ { "nodeId": "n1", "level": "INFO", "message": "…" } ]
  }
}
```

| 组成 | 来源 | 排序 |
| --- | --- | --- |
| `inst` | `InstDao.findById` | — |
| `tasks` | `TaskDao.findByInstId` | 库内顺序 |
| `logs` | `InstLogDao.findByInstIdOrderByCreateDateAsc` | 创建时间升序 |

> 实例不存在 → 400「实例不存在」。

### 4.3 `/inst/suspend`（挂起实例）

| 步骤 | 行为 |
| --- | --- |
| 校验 | 仅 `state = WAITE` / `RUNNING` 可挂起，否则 400「仅运行中或等待中的实例可以挂起」 |
| 变更 | `state = SUSPEND` 并落库；不打断在途节点 |
| 后续 | 下一个节点被消费时检测到 `SUSPEND`，把「节点名 + 循环次数」记入 `suspendNodes` 后 ack，不再调度下游 |

### 4.4 `/inst/resume`（恢复挂起实例）

| 步骤 | 行为 |
| --- | --- |
| 校验 | 仅 `state = SUSPEND` 可恢复，否则 400「仅挂起实例可以恢复」 |
| 变更 | `state = RUNNING`、清空 `suspendNodes` 并落库 |
| 续跑 | 按 `suspendNodes` 记录的节点名 + 循环次数回查任务，`sendTaskResult` 重投**结果**继续调度下游（节点已执行完，重投执行会重复副作用）；记录为空时只置回运行中 |
| 失败 | `suspendNodes` 非空但匹配不到任务 → 500「挂起节点未找到对应任务」 |

### 4.5 `/inst/cancel`（取消实例）

| 步骤 | 行为 |
| --- | --- |
| 校验 | `SUCCESS` / `FAIL` / `CANCEL` 等终态不可取消，否则 400「终态实例不能取消」 |
| 变更 | `state = CANCEL`、`endDate = 当前时间`、`failReason = "cancelled"`，落库 |

> 取消是**状态驱动**：已在消费线程中执行的任务不会被强制中断，后续节点不再调度。

### 4.6 `/inst/delete`（删除实例，级联清理）

请求：

```http
POST /servicearrange/inst/delete HTTP/1.1
Content-Type: application/json

["6650f3a1c2b3d4e5f6a7b8c9"]
```

| 步骤 | 行为 |
| --- | --- |
| 校验 | `ids` 为空 → 400「请指定要删除的实例 id」；实例 `state = RUNNING` 且未传 `force=true` → 400「实例正在运行，不能删除」 |
| 删除顺序 | 任务（`TaskDao.deleteByInstId`）→ 日志（`InstLogDao.deleteByInstId`）→ 实例（`InstDao.deleteById`）→ 本地文件（`LocalFileStorage.deleteByInstId` 整目录删除） |
| 不存在的 id | 跳过，不报错 |
| 响应 | `{code:200,msg:"success",data:{instCount,taskCount,logCount,fileCount}}` |

> 库记录先删、文件后删：文件删除失败只会残留孤儿文件，不会出现「实例没了、文件还挂在别处」的脏数据。
> 删除文件只作用于 `{servea.file.root}/{instId}` 目录，不影响其它实例。

---

## §5 实例日志

| 项 | 值 |
| --- | --- |
| 路径 | `/instlog/list` |
| 方法 | `GET` |
| 参数 | `instId`（必填）、`nodeId`（可选）|
| 响应 | `{code,msg,data:[InstLog…]}`，按 `createDate` 升序 |

| 场景 | 查询方法 |
| --- | --- |
| 不传 `nodeId` | `findByInstIdOrderByCreateDateAsc(instId)`（实例级日志）|
| 传 `nodeId` | `findByInstIdAndNodeIdOrderByCreateDateAsc(instId, nodeId)`（算子级日志）|

`InstLog` 字段（`instId` / `planId` / `nodeId` / `taskId` / `level` / `message`）见 [数据模型与消息模型](04-数据模型与消息模型.md) §5。

---

## §6 健康检查

| 项 | 值 |
| --- | --- |
| 路径 | `/health` |
| 方法 | `GET` |
| 响应 | `{code:200,msg:"success",data:{status:"UP",mongo:"UP"\|"DOWN"}}` |

```json
{ "code": 200, "msg": "success", "data": { "status": "UP", "mongo": "UP" } }
```

> `mongo` 通过 `MongoTemplate.getDb().runCommand({ping:1})` 探活；MongoDB 不可用时为 `"DOWN"`，但接口本身仍返回 200。
> 前端连通性检测优先打 `/health`，失败时回退 `/registerOperator/findById?id=__ping__`。

---

## §7 保存算子注册

### 7.1 基本信息

| 项 | 值 |
| --- | --- |
| 路径 | `/registerOperator/save` |
| 方法 | `POST` |
| 内容类型 | `application/json` |
| 请求体 | `@RequestBody RegisterInfoEntity` |
| 响应 | 裸字符串：成功 `"success"`，失败为异常消息文本 |

### 7.2 请求体（`RegisterInfoEntity`，已核对源码）

字段**全部来自 `RegisterInfoEntity`**，与早期文档中的 `name`/`url`/`method` 不同：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `String` | 主键；为空则新增，非空则更新（继承自 `BaseEntity`）|
| `type` | `String` | 类型：`http` / `rpc` 等 |
| `serviceName` | `String` | API 名称 |
| `serviceUrl` | `String` | 源路径 |
| `requestMethod` | `String` | 请求方法：`post` / `get` 等 |
| `description` | `String` | 描述 |
| `catalogueId` | `String` | 目录 id |
| `reqHeaders` | `String` | 入参 headers（JSON 字符串），如 Content-Type |
| `reqQuery` | `String` | 入参 query / urlpath 参数（JSON 字符串）|
| `reqBodyType` | `String` | 入参 body 格式：`json` / `text` / `xml` |
| `reqBodyIsJsonSchema` | `Boolean` | 入参是否启用 JsonSchema |
| `reqBodyData` | `String` | 入参 body 数据（启用 Schema 后为 Schema 结构）|
| `resBodyType` | `String` | 出参类型：`json` / 二进制流等 |
| `resBodyIsJsonSchema` | `Boolean` | 出参是否启用 JsonSchema |
| `resBodyData` | `String` | 出参数据（json 时为 Schema）|
| `mockParam` | `String` | mock 测试参数 |
| `timeOut` | `String` | 超时设置 |
| `checkJsonSchema` | `String` | 服务编排校验用 Schema |
| `htmlPath` | `String` | html 路径 |
| `sheelFtpPath` | `String` | shell ftp 路径 |
| `remark` | `String` | 备注（继承自 `BaseEntity`）|

请求示例（来自源码 `@api` 注释）：

```json
{
  "type": "http",
  "serviceName": "百度搜索",
  "serviceUrl": "www.baidu.com",
  "requestMethod": "post",
  "description": "服务编排百度搜索api登记",
  "catalogueId": "#0",
  "reqQuery": "[{\"required\":\"1\",\"name\":\"id\",\"example\":\"?id=224\",\"desc\":\"\"}]",
  "reqHeaders": "[{\"required\":\"1\",\"name\":\"Content-Type\",\"value\":\"application/x-www-form-urlencoded\"}]",
  "reqBodyType": "json",
  "reqBodyData": "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\",\"description\":\"修改传目录Id 新增不传\"},\"name\":{\"type\":\"string\",\"description\":\"目录名\"}},\"required\":[\"name\"]}",
  "reqBodyIsJsonSchema": true,
  "resBodyType": "json",
  "resBodyData": "{\"type\":\"string\",\"description\":\"成功返回success\"}",
  "resBodyIsJsonSchema": true
}
```

### 7.3 响应

| 返回值 | 含义 |
| --- | --- |
| `"success"` | 保存成功 |
| 异常消息文本 | 保存失败（直接返回异常 `message`）|

> `createDate` / `modifyDate` 由 `RegisterInfoServiceImpl.save` 统一写入。

---

## §8 批量删除算子注册

### 8.1 基本信息

| 项 | 值 |
| --- | --- |
| 路径 | `/registerOperator/delete` |
| 方法 | `POST` |
| 内容类型 | `application/json;charset=UTF-8` |
| 请求体 | `@RequestBody List<String>` |

### 8.2 请求体

```json
["6650f3a1c2b3d4e5f6a7b8c9", "6650f3a1c2b3d4e5f6a7b8ca"]
```

### 8.3 响应

| 返回值 | 含义 |
| --- | --- |
| `"success"` | 删除成功 |
| 异常消息文本 | 删除失败 |

---

## §9 查询算子注册

### 9.1 按 id 查询

| 项 | 值 |
| --- | --- |
| 路径 | `/registerOperator/findById` |
| 方法 | `GET`（`@RequestMapping` 未限定方法，GET/POST 均可）|
| 参数 | `id`（**查询参数**，非路径变量）|

> 注意：`@api` 注释写的是 `findById/{id}`（路径变量），但**实际实现是 `findById(String id)` 查询参数**。以实际实现为准。

```http
GET /servicearrange/registerOperator/findById?id=6650f3a1c2b3d4e5f6a7b8c9 HTTP/1.1
```

返回完整的 `RegisterInfoEntity` 对象（字段见 7.2），未命中时返回 `null`：

```json
{
  "id": "6650f3a1c2b3d4e5f6a7b8c9",
  "type": "http",
  "serviceName": "百度搜索",
  "serviceUrl": "www.baidu.com",
  "requestMethod": "post",
  "catalogueId": "#0",
  "reqBodyType": "json",
  "reqBodyIsJsonSchema": true,
  "resBodyType": "json",
  "resBodyIsJsonSchema": true,
  "createDate": "2026-09-14T10:20:30.000+08:00",
  "modifyDate": "2026-09-14T10:20:30.000+08:00"
}
```

### 9.2 列表

| 项 | 值 |
| --- | --- |
| 路径 | `/registerOperator/list` |
| 方法 | `GET` / `POST` 均可 |
| 响应 | `{code:200,msg:"success",data:[RegisterInfoEntity…]}` |

---

## §10 错误处理

已引入全局异常处理器 `GlobalExceptionHandler`（`@RestControllerAdvice`），统一把异常转为 `{code,msg,data}`：

| 异常类型 | HTTP 状态 | 响应体 |
| --- | --- | --- |
| `IllegalArgumentException` | `400` | `{"code":400,"msg":"<message>","data":null}` |
| 其他 `Exception` | `500` | `{"code":500,"msg":"<message>","data":null}` |

已由 400 覆盖的常见场景：

| 场景 | 消息 |
| --- | --- |
| `get_result` 未传 `servea_instId` | `get_result 需要 servea_instId` |
| 实例不存在 / 未成功 | `实例不存在：{id}` / `实例还未运行成功，无输出信息！` |
| 流程停用 / 未发布 | `流程已停用，无法运行` / `仅允许运行已发布流程，当前状态=…` |
| DSL 找不到 | `找不到流程 DSL，planId=…` |
| DSL 缺开始节点 | `DSL 缺少可用的开始节点` |
| 模型不存在 | `流程不存在` |
| 空 DSL 发布 | `空 DSL 不能发布` |
| 实例状态不允许干预 | `仅挂起实例可以恢复` / `终态实例不能取消` |
| 入参类型转换失败 | `参数 {name} 无法按类型 {type} 转换：…` |

---

## §11 前端对接清单

前端 `frontend/js/api.js` 的策略：**「接口设置」（`apiBase`）非空即走远程**，失败自动回退本地 `Store` 并标记 `local: true`。

| 页面 | 调用的接口 | 状态 |
| --- | --- | --- |
| 实例/流程列表 | `GET /plan/list` | ✅ 已对接（远程优先，失败兜底本地）|
| 流程详情 | `GET /plan/findById?id=` | ✅ 已对接 |
| 流程保存 | `POST /plan/save` | ✅ 已对接（本地同时写 Store）|
| 流程发布 | `POST /plan/publish?id=` | ✅ 已对接 |
| 流程删除 | `POST /plan/delete` | ✅ 已对接 |
| 设计器 · 试运行 / 运行实例 | `POST /inst/run/or/getResult/{planId}` | ✅ 已对接（`servea_optType=run`）|
| 运行记录列表 | `GET /inst/list?planId=` | ✅ 已对接 |
| 运行详情（含任务与日志）| `GET /inst/detail?id=` | ✅ 已对接 |
| 日志（实例级 / 算子级）| `GET /instlog/list?instId=`（+`nodeId`）| ✅ 已对接 |
| 连通性检测 | `GET /health`，失败回退 `/registerOperator/findById?id=__ping__` | ✅ 已对接 |
| 算子注册 · 新增 / 删除 / 详情 | `POST /registerOperator/save` · `/delete` · `GET /findById` | ✅ 已对接（字段已按真实实体）但 `REMOTE.operator` 仍为 `false`（字段模型未对齐，走本地兜底）|

**仍为「建议契约」、后端未实现**（前端写好方法但开关关闭）：

| 建议路径 | 用途 | 前端开关 |
| --- | --- | --- |
| `POST /operator/register` | 注册为跨实例复用算子 | `REMOTE.operator` |
| `GET /env/list` · `POST /env/save` · `POST /env/delete` · `POST /env/test` | shell / sql 执行环境 CRUD 与连通性测试 | `REMOTE_ENV` |

> 算子注册的真实路径是 `/registerOperator/*`（§7~§9），与前端建议契约中的 `/operator/*` **不同名且字段模型不同**，切换前需先对齐。

---

## §12 统一返回结构

**已落地**：

```json
{ "code": 200, "msg": "success", "data": { } }
```

| 范围 | 是否包装 |
| --- | --- |
| `/plan/*`、`/inst/list`、`/inst/detail`、`/inst/suspend`、`/inst/resume`、`/inst/cancel`、`/inst/delete`、`/instlog/list`、`/health`、`/registerOperator/list` | ✅ 全部包装 |
| `/inst/run/or/getResult/{planId}`（`run` 分支与幂等命中）| ✅ 包装，`data` 含 `id`/`state`/`traceId`/`planVersion`/`idempotentHit` |
| `/inst/run/or/getResult/{planId}`（`get_result` 成功）| ❌ 直接返回业务数据（JSON / 文本 / 文件流）|
| `/registerOperator/save` · `/delete` · `/findById` | ❌ 返回裸值（`"success"` / 实体 / `null`）|

> 前端 `request()` 已做兼容：响应体若含 `code` 且含 `data` 字段，则自动解包并取用 `data`；否则按原始响应处理。

---

## 📚 相关文档
- 💻 [前端控制台说明](06-前端控制台说明.md) §7 — 前端如何对接这些接口
- 🔗 [DSL规范](02-DSL规范.md) — `plan.dsl` 内容的唯一契约
- ⚙️ [配置说明](12-配置说明.md) §3.3.1 — `task.orchestration.*` / `task.backpressure.*`
- 🗂️ [数据模型与消息模型](04-数据模型与消息模型.md) — `Inst` / `Task` / `InstLog` / `Plan` 字段
- 🧪 [测试指南](14-测试指南.md) §4 — 接口验证用例
- 🚀 [快速开始](11-快速开始.md) §4 — 接口验证命令
