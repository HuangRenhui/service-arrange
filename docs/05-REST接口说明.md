# REST 接口说明

本文档列出 `web` 模块暴露的全部 HTTP 接口。

> 代码依据：`backend/web/src/main/java/com/hrh/servicearrange/controller/*`（已逐行核对）。
> 部署信息：端口 `8082`，上下文路径 `/servicearrange`（见 `application.yml`）。

---

## 📋 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档名称 | REST接口说明 |
| 文档版本 | v1.0 |
| 最后更新 | 2026-09-14 |
| 状态 | 现行 |
| 接口总数 | 4 个 |
| 相关代码 | `main/**/controller/InstController.java` · `RegisterInfoController.java` |

---

## 目录

- [§0 部署上下文](#§0-部署上下文)
- [§1 接口总览](#§1-接口总览)
- [§2 运行实例 / 查询结果](#§2-运行实例--查询结果)
- [§3 保存算子注册](#§3-保存算子注册)
- [§4 批量删除算子注册](#§4-批量删除算子注册)
- [§5 按 id 查询算子注册](#§5-按-id-查询算子注册)
- [§6 错误处理现状](#§6-错误处理现状)
- [§7 前端对接清单](#§7-前端对接清单)
- [§8 建议的统一接口契约](#§8-建议的统一接口契约改造参考)

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
| 2 | POST | `/servicearrange/registerOperator/save` | 保存算子注册信息 | `RegisterInfoController` |
| 3 | POST | `/servicearrange/registerOperator/delete` | 批量删除算子注册信息 | `RegisterInfoController` |
| 4 | GET | `/servicearrange/registerOperator/findById` | 按 id 查询算子注册信息 | `RegisterInfoController` |

---

## §2 运行实例 / 查询结果

### 2.1 基本信息

| 项 | 值 |
| --- | --- |
| 路径 | `/inst/run/or/getResult/{planId}` |
| 方法 | `POST` |
| 内容类型 | `multipart/form-data`（推荐）或无 body 的普通 POST |
| 路径参数 | `planId`：流程（模型）id |

> ⚠️ **重要**：接口实际从请求体中读取的是**保留字段 + 业务入参**，且业务入参的**类型还原**依赖开始节点的 `inputsJsonSchema`。详见 2.2。

### 2.2 参数说明

#### 2.2.1 保留字段（由框架识别，不进入流程入参逻辑）

| 参数 | 类型 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `servea_optType` | `String` | 否 | `"run"` | `run` = 运行实例；`get_result` = 查询结果。**注意前缀 `servea_`** |
| `servea_sync` | `Boolean` | 否 | `true` | 是否同步。**注意默认值为 `true`** |
| `servea_instName` | `String` | 否 | — | 实例名称（写入 `InstRunParamsVo.instName`） |

#### 2.2.2 业务入参

除保留字段外，**其余字段均为流程入参**，键名应与开始节点 `inputsJsonSchema.properties` 的字段名一致。

类型还原规则（`InstController` L98-132，依据 `DslParser.getDslStartParamsTypeMap`）：

| Schema 声明的 type | 还原为 |
| --- | --- |
| `text` / `String` | 字符串 |
| `array` / `jsonArrStr` | JSON 数组（空串 → `null`）|
| `number` | `Long`（空串 → `0`）|
| `boolean` | `Boolean`（空串 → `false`）|
| `object` / `jsonObjStr` | JSON 对象（空串 → `null`）|
| 其他 / 未声明 | 原样字符串 |

> 依赖 `JsonSchemaUtil.jsonConfig`（`setIgnoreNullValue(false)`），保证 `null` 值不被丢弃。

#### 2.2.3 请求头

`InstController` 会**采集全部请求头**存入 `Inst.headerParams`（同名头用逗号合并），供 `#header_$Xxx` 表达式取值。无需在 body 中重复传递。

#### 2.2.4 文件上传

`multipart/form-data` 中的文件字段会被读取（`multiFileMap`），但当前实现**仅打印文件名与大小**（L139-146），实际未落盘，字段值被置为固定字符串 `"path"`。

### 2.3 请求示例

**运行业例**

```http
POST /servicearrange/inst/run/or/getResult/PLAN_2026_001 HTTP/1.1
Host: localhost:8082
Content-Type: multipart/form-data; boundary=----X
X-AToken: abc123

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

> ℹ️ **注意**：当前实现**不读取 `servea_instId`**。`get_result` 分支中查询的是**本次请求新建实例的 id**（`inst.getId()`，L178-189），而非传入的已有实例 id。因此该接口当前只能「边运行边取结果」，无法查询历史实例。见 2.6 已知限制。

### 2.4 响应示例

响应结构取决于 `contentType`：

**业务返回值为 JSON 对象/数组**（`outputs.contentType == "application/json"`）

```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "total": 42 }
```

> 直接返回解析后的 JSON，**无 `{code,msg,data}` 包装**。

**非 JSON 类型**：返回 `instId` 字符串（L186，`respResult` 初值为 `inst.getId()`）。

**`contentType` 为非 JSON 且路径以 `filemgr://` 开头**：进入文件下载分支，设置 `content-disposition` 头，但**下载逻辑为 TODO 未实现**（L210-229），且此分支中 `fileName` 可能为 `null` 导致 `URLEncoder.encode` 抛 NPE。

**查询结果但实例未成功**

```http
HTTP/1.1 500 Internal Server Error

实例还未运行成功，无输出信息！
```

> 直接抛 `RuntimeException`（L181），无全局异常处理器，Spring 返回 500 错误页。

### 2.5 行为说明

| `servea_optType` | 行为 |
| --- | --- |
| `run` | 解析 DSL → 生成 `Inst` 落库（先存 WAITE，再置 RUNNING）→ 取 `roots` 首元素为开始节点 → `StartTask.convert` → `taskProductor.sendTaskResult` 触发执行 |
| `get_result` | 读本次实例状态，非 `SUCCESS` 抛异常；`SUCCESS` 则返回实例 `outputs` |

### 2.6 已知限制

| 项 | 说明 |
| --- | --- |
| **DSL 来源** | **硬编码** `FileUtil.readUtf8String("hrh_http.json")`（L80），未按 `planId` 关联模型 |
| **无法查历史实例** | `get_result` 不使用 `servea_instId`，只查本次新建的实例 |
| **同步语义** | `servea_sync` 仅写入 `Inst.sync` 字段，Controller **未实现等待轮询**，请求立即返回 |
| 参数前缀 | 保留字段前缀为 `servea_`（非 `servea_optType` 之外的通用命名） |
| 内容类型校验 | 非 multipart 的 `RuntimeException` 分支被注释（L77），静默降级 |
| 返回结构不统一 | 无 `{code,msg,data}` 包装，异常直接抛 |
| 文件上传 | 仅打印，未落盘 |
| 文件下载 | 未实现，且存在 NPE 风险 |

---

## §3 保存算子注册

### 3.1 基本信息

| 项 | 值 |
| --- | --- |
| 路径 | `/registerOperator/save` |
| 方法 | `POST` |
| 内容类型 | `application/json` |
| 请求体 | `@RequestBody RegisterInfoEntity` |

### 3.2 请求体（`RegisterInfoEntity`，已核对源码）

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

### 3.3 响应

| 返回值 | 含义 |
| --- | --- |
| `"success"` | 保存成功 |
| 异常消息文本 | 保存失败（直接返回异常 `message`）|

> `createDate` / `modifyDate` 由 `RegisterInfoServiceImpl.save` 统一写入。

---

## §4 批量删除算子注册

### 4.1 基本信息

| 项 | 值 |
| --- | --- |
| 路径 | `/registerOperator/delete` |
| 方法 | `POST` |
| 内容类型 | `application/json;charset=UTF-8` |
| 请求体 | `@RequestBody List<String>` |

### 4.2 请求体

```json
["6650f3a1c2b3d4e5f6a7b8c9", "6650f3a1c2b3d4e5f6a7b8ca"]
```

### 4.3 响应

| 返回值 | 含义 |
| --- | --- |
| `"success"` | 删除成功 |
| 异常消息文本 | 删除失败 |

---

## §5 按 id 查询算子注册

### 5.1 基本信息

| 项 | 值 |
| --- | --- |
| 路径 | `/registerOperator/findById` |
| 方法 | `GET`（`@RequestMapping` 未限定方法，GET/POST 均可）|
| 参数 | `id`（**查询参数**，非路径变量）|

> 注意：`@api` 注释写的是 `findById/{id}`（路径变量），但**实际实现是 `findById(String id)` 查询参数**（L107-110）。以实际实现为准。

### 5.2 请求示例

```http
GET /servicearrange/registerOperator/findById?id=6650f3a1c2b3d4e5f6a7b8c9 HTTP/1.1
```

### 5.3 响应示例

返回完整的 `RegisterInfoEntity` 对象（字段见 3.2）：

```json
{
  "id": "6650f3a1c2b3d4e5f6a7b8c9",
  "type": "http",
  "serviceName": "百度搜索",
  "serviceUrl": "www.baidu.com",
  "requestMethod": "post",
  "description": "服务编排百度搜索api登记",
  "catalogueId": "#0",
  "reqBodyType": "json",
  "reqBodyIsJsonSchema": true,
  "resBodyType": "json",
  "resBodyIsJsonSchema": true,
  "createDate": "2026-09-14T10:20:30.000+08:00",
  "modifyDate": "2026-09-14T10:20:30.000+08:00"
}
```

未命中时返回 `null`。

---

## §6 错误处理现状

当前**没有**全局异常处理器（无 `@ControllerAdvice`），也**没有**统一的 `{code,msg,data}` 返回结构。

| 情况 | 实际表现 |
| --- | --- |
| 业务异常（如实例未成功） | 抛 `RuntimeException`，Spring 返回 HTTP 500 |
| 参数缺失 | 依具体分支：部分静默降级、部分 NPE |
| 实例不存在 | 依具体分支处理 |
| 成功 | 直接返回业务数据（JSON 对象 / `instId` 字符串 / `"success"`）|

> 下文第 8 节给出**建议的统一契约**，供后端改造时参考；前端已按实际响应做兼容处理。

---

## §7 前端对接清单

| 页面 | 调用的接口 | 状态 |
| --- | --- | --- |
| 流程设计器 · 试运行 | `POST /inst/run/or/getResult/{planId}` | ✅ 已对接 |
| 运行实例 · 提交运行 | 同上（`servea_optType=run`）| ✅ 已对接 |
| 运行实例 · 查看结果 | 同上（`servea_optType=get_result`）| ⚠️ 受限于后端无法查历史实例 |
| 算子注册 · 新增/编辑 | `POST /registerOperator/save` | ✅ 已对接（字段已按真实实体） |
| 算子注册 · 删除 | `POST /registerOperator/delete` | ✅ 已对接 |
| 算子注册 · 详情 | `GET /registerOperator/findById?id=` | ✅ 已对接 |

> 「模型管理（Plan CRUD）」「实例列表」「日志查询」接口**后端尚未提供**，前端未实现对应页面。

---

## §8 建议的统一接口契约（改造参考）

供后端后续补齐时参考，非当前实现：

| 建议路径 | 方法 | 用途 |
| --- | --- | --- |
| `/plan/save` | POST | 保存流程模型 |
| `/plan/delete` | POST | 批量删除流程模型 |
| `/plan/findById` | GET | 查询流程模型 |
| `/plan/list` | GET | 流程模型列表 |
| `/inst/list` | GET | 实例列表（按 planId/state 过滤）|
| `/inst/detail` | GET | 实例详情（含各节点任务状态）|
| `/inst/resume` | POST | 恢复挂起实例 |
| `/instlog/list` | GET | 日志查询 |

建议统一返回：

```json
{ "code": 200, "msg": "success", "data": { } }
```

并新增 `@ControllerAdvice` 统一转换异常。

---

## 📚 相关文档
- 💻 [前端控制台说明](06-前端控制台说明.md) §7 — 前端如何对接这些接口
- ⚙️ [配置说明](12-配置说明.md) §2 — 端口与上下文路径
- 🗂️ [数据模型与消息模型](04-数据模型与消息模型.md) — 实例与算子具体字段
- 🧪 [测试指南](14-测试指南.md) §4 — 接口验证用例
- 🚀 [快速开始](11-快速开始.md) §4 — 接口验证命令
