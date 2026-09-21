# DSL规范

本文档描述服务编排引擎的领域专用语言（DSL）。前端画布产出的 JSON、后端 `DslParser` 解析的对象、以及运行时落库的 `Inst`，都以本规范为唯一契约。

> 代码依据：`backend/engine/src/main/java/com/hrh/servicearrange/dsl/*`、`parser/annotation/CellType.java`。

---

## 📋 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档名称 | DSL规范 |
| 文档版本 | v1.0 |
| 最后更新 | 2026-09-14 |
| 状态 | 现行 |
| 契约地位 | **全系统唯一契约**，前端导出与后端解析均以此为准 |
| 相关代码 | `dsl/*.java`、`parser/annotation/CellType.java` |

---

## 目录

- [§1 总体结构](#§1-总体结构)
- [§2 Cell（通用节点/连线）](#§2-cell通用节点连线)
- [§3 cellType 取值表](#§3-celltype-取值表)
- [§4 各节点 data 结构](#§4-各节点-data-结构)
- [§5 Group（强组合 / 补偿组）](#§5-group强组合--补偿组)
- [§6 完整示例](#§6-完整示例)
- [§7 校验规则](#§7-校验规则前端需实现)

---

## §1 总体结构

一个编排（流程）用一份 JSON 描述，顶层结构为 `DSL`：

```json
{
  "cells": [ /* 节点 + 连线，平铺在一个数组里，用 cellType 区分 */ ],
  "groups": [ /* 强组合（事务补偿组） */ ],
  "dynamicGlobalParameters": [ /* 运行期可读写的全局变量，由引擎写入 */ ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `cells` | `Cell[]` | 画布中的全部元素：普通节点与连线混排，通过 `cellType` 区分 |
| `groups` | `Group[]` | 强组合定义，目前仅用于「补偿组」 |
| `dynamicGlobalParameters` | `KeyValueDto[]` | 运行期动态全局参数，初始为空数组 |

> **关键设计：连线和节点同构**。连线本身也是一个 `Cell`，通过 `source` / `target` 指向节点，通过 `ports` 被节点引用。这样 `cells` 是一张扁平数组，解析时无需单独的 edges 字段。

## §2 Cell（通用节点/连线）

所有元素继承自 `Cell`（`dsl/Cell.java`）。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | `string` | 是 | 元素唯一 ID（前端建议 UUID） |
| `name` | `string` | 否 | 展示名称；为空时引擎回退为 `cellType_id` |
| `cellType` | `string` | 是 | 元素类型，默认 `edge_common`，取值见第 3 节 |
| `data` | `object` | 否 | 类型相关的业务配置，结构随 `cellType` 变化 |
| `source` | `EdgeEndpoint` | 连线必填 | 连线起点 |
| `target` | `EdgeEndpoint` | 连线必填 | 连线终点 |
| `ports` | `Port` | 否 | 节点桩点（前端连线锚点），仅节点使用 |
| `groupIds` | `string[]` | 否 | 该节点所属强组合 id 集合 |

**EdgeEndpoint**（`source` / `target` 的结构）：

```json
{ "cell": "节点id", "port": "桩点id" }
```

**Port**（节点的桩点集合）：

```json
{ "items": [ { "id": "桩点id" }, { "id": "桩点id" } ] }
```

> 桩点就是前端节点四周的连线小圆点。连线通过 `source.port` / `target.port` 精确锚定到某个桩点，因此一个节点可以有多条出线（例如 HTTP 节点同时挂「成功线」和「补偿线」）。

## §3 cellType 取值表

定义于 `parser/annotation/CellType.java`。

### 3.1 节点

| 常量 | 字面值 | 含义 |
| --- | --- | --- |
| `START` | `node_start` | 开始节点（全局唯一） |
| `END` | `node_end` | 结束节点（全局唯一） |
| `GLOBAL_DATA` | `global_Data` | 全局参数节点（解析时被单独抽出，不参与 DAG） |

### 3.2 连线

| 常量 | 字面值 | 含义 |
| --- | --- | --- |
| `EDGE_COMMON` | `edge_common` | 普通线，顺序流转 |
| `EDGE_LOOP` | `edge_loop` | 循环线，定义循环区间 |
| `EDGE_DECISION` | `edge_decision` | 决策线，条件成立才走 |
| `EDGE_COMPENSATE` | `edge_compensate` | 补偿线，失败后触发补偿分支 |

### 3.3 内部算子节点

| 常量 | 字面值 | 含义 |
| --- | --- | --- |
| `FUN_DATAMAP` | `node_inner_datamap` | 数据映射（组装下游入参） |
| `FUN_DECISION` | `node_inner_decision` | 决策（多分支判断） |
| `FUN_SLEEP` | `node_inner_sleep` | 睡眠/延时 |
| `FUN_TRANSFORM` | `node_inner_transform2Obj` | 规则转换（JSON 结构化改写） |
| `FUN_DATARESULT` | `node_inner_dataresult` | 数据结果节点 |
| `FUN_COMPENSATE_DATAMAP` | `node_inner_compensateDatamap` | 补偿数据映射 |
| `FUN_JSON2XML` | `node_inner_convertJson2Xml` | JSON 转 XML |
| `FUN_XML2JSON` | `node_inner_convertXml2Json` | XML 转 JSON |

### 3.4 脚本类节点

| 常量 | 字面值 | 含义 |
| --- | --- | --- |
| `FUN_SHELL` | `node_inner_execShell` | 执行 Shell |
| `FUN_JAR` | `node_inner_execJar` | 执行 Jar |
| `FUN_PYTHON` | `node_inner_execPython` | 执行 Python |

### 3.5 外部算子节点

| 常量 | 字面值 | 含义 |
| --- | --- | --- |
| `OPERATOR_HTTP` | `node_outer_http` | HTTP 调用 |
| `OPERATOR_DUBBO` | `node_outer_dubbo` | Dubbo 调用 |
| `OPERATOR_WEBSERVICE` | `node_outer_webservice` | WebService 调用 |

### 3.6 补偿算子节点

| 常量 | 字面值 | 含义 |
| --- | --- | --- |
| `OPERATOR_HTTP_COMPENSATE` | `node_outer_httpCompensate` | HTTP 补偿 |
| `OPERATOR_DUBBO_COMPENSATE` | `node_outer_dubboCompensate` | Dubbo 补偿 |
| `OPERATOR_WEBSERVICE_COMPENSATE` | `node_outer_webserviceCompensate` | WebService 补偿 |

> **约定**：任务类型（`Task.type`）= `cellType + "_task"`，见 `TaskType.java`。例如 `node_start_task`、`node_outer_http_task`。Spring 容器中的转换器 Bean 名即用该值注册，例如 `@Service(TaskType.START)`。

## §4 各节点 data 结构

### 4.1 开始节点 `node_start`（StartCell）

```json
{
  "cellType": "node_start",
  "id": "2d557d74-...",
  "name": "开始",
  "data": {
    "inputsJsonSchema": "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}",
    "globals": [
      { "name": "类别", "key": "wftype", "type": "int", "value": 1 }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `inputsJsonSchema` | `string` | **字符串形式的 JSON Schema**，声明流程启动入参结构；前端据它渲染「运行实例」表单 |
| `globals` | `KeyValueDto[]` | 流程级常量，随开始节点带入 |

`KeyValueDto`：`{ name, key, type, value, defaultValue }`。

### 4.2 结束节点 `node_end`（EndCell）

```json
{
  "cellType": "node_end",
  "data": {
    "contentType": "application/json",
    "outputsJsonSchema": "{...}",
    "valueJsonpathMapping": "#nodeId$jsonpath"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `contentType` | 出参内容类型 |
| `outputsJsonSchema` | 字符串形式的 JSON Schema，声明流程返回值结构 |
| `valueJsonpathMapping` | 返回值取值表达式（JSONPath 映射） |

### 4.3 决策线 `edge_decision`（EdgeDecisionCell）

```json
{
  "cellType": "edge_decision",
  "id": "db553136-...",
  "name": "决策线",
  "source": { "cell": "78ff68be-...", "port": "1621f25b-..." },
  "target": { "cell": "b1bbfdc4-...", "port": "f90f2849-..." },
  "data": {
    "conditionData": [
      {
        "eventContent": "",
        "type": "",
        "condition": "!=null",
        "logic": "none",
        "Rjsonpath": "#start_$where"
      }
    ],
    "L": [null],
    "R": ["#start_$where"],
    "jsonpathElexpression": "R0!=L0"
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `conditionData[]` | 结构化条件列表（供前端回显） |
| `conditionData[].condition` | 比较符：`!=null`、`> `、`>=`、`<`、`<=`、`!=`、`==` 等 |
| `conditionData[].logic` | 与上一条件的关系：`none` / `&&`(且) / `\|\|`(或) |
| `conditionData[].Rjsonpath` | 左操作数取值表达式 |
| `R[]` / `L[]` | 参与运算的表达式集合；`R0` 表示 `R[0]`，`L0` 表示 `L[0]` |
| `jsonpathElexpression` | 最终判定表达式，如 `R0>100 \|\| R1<90` |

> **取值表达式语法**：`#节点id$jsonpath`。`$` 之后是相对该节点输出的 JSONPath；`#start_$where` 表示取开始节点输出里的 `where` 字段。`#header_$X-AToken` 表示取请求头。

### 4.4 循环线 `edge_loop`（EdgeLoopCell）

```json
{
  "cellType": "edge_loop",
  "data": {
    "loopType": "doWhile",
    "transformRules": [],
    "R": [],
    "L": [],
    "jsonpathElexpression": "",
    "cycleInfo": {
      "jsonpathSelect": "#dynamicParams_$idx",
      "rule": "+",
      "type": "doWhile",
      "stepLength": 1
    }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `loopType` | `doWhile`（先执行后判断，可引用区间内节点输出）或 `whileDo`（先判断后执行，**只能引用动态全局参数**） |
| `cycleInfo.jsonpathSelect` | 循环计数变量的取值路径，如 `#dynamicParams_$idx` |
| `cycleInfo.rule` | 计数递推规则，如 `+` |
| `cycleInfo.stepLength` | 步长 |
| `jsonpathElexpression` / `R` / `L` | 循环退出条件，语义同决策线 |
| `transformRules` | 每次迭代前的数据转换规则（复用规则转换节点的 `TransformRule` 结构） |

循环区间由引擎自动推导：循环线 `source` 的祖先节点集合 与 `target` 的后代节点集合 的交集，详见 `DslParser#getCellIdsBetweenLoopEdge`。

### 4.5 数据映射 `node_inner_datamap`（FunDatamapCell）

```json
{
  "cellType": "node_inner_datamap",
  "data": {
    "parentsOutputs": [ { "contentType": "application/json", "outputsJsonSchema": "{...}" } ],
    "childInputs": {
      "reqPath":   [ { "key": "id", "jsonpathMapping": "#start_$id" } ],
      "reqQuery":  null,
      "reqHeaders":[ { "key": "X-AToken", "jsonpathMapping": "#header_$X-AToken" } ],
      "reqBodyForm": null,
      "reqBodyOther": "{\"type\":\"object\",\"properties\":{...}}"
    }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `parentsOutputs` | 上游节点的出参声明（`EndCell.Data` 结构）列表 |
| `childInputs.reqPath/reqQuery/reqHeaders/reqBodyForm` | 键值对列表，`KeyValueWithJsonPathDto`，含 `jsonpathMapping` 取值表达式 |
| `childInputs.reqBodyOther` | 请求体 JSON Schema（字符串） |

### 4.6 规则转换 `node_inner_transform2Obj`（FunTransform2ObjCell）

```json
{
  "cellType": "node_inner_transform2Obj",
  "data": {
    "transformRuleList": [
      {
        "key": "total",
        "keyType": "number",
        "type": "compute",
        "jsonpathMapping": "R0+R1",
        "defaultValue": 0
      }
    ]
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `key` | 新对象的目标字段名 |
| `keyType` | 目标字段类型（`string`/`number`/`boolean`/...） |
| `type` | 规则类型：`default`（直取）/ `compute`（计算）/ `str_append`（字符串拼接）/ `list_convert`（转数组） |
| `jsonpathMapping` | 表达式 |
| `defaultValue` | 兜底默认值 |

### 4.7 全局参数节点 `global_Data`（GlobalDataCell）

```json
{
  "cellType": "global_Data",
  "data": {
    "staticParams":  [ { "name": "text", "type": "string", "default": "111" } ],
    "dynamicParams": [ { "name": "idx",  "type": "number", "default": 0 } ]
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `staticParams` | 静态全局参数（常量），解析后进入 `Inst.staticParams` |
| `dynamicParams` | 动态全局参数（变量），解析后进入 `Inst.dynamicParams`，运行期可被循环线等更新 |

> 解析规则：`key = name`，`value = defaultValue = default`。见 `DslParser#parser` 第 192-229 行。

### 4.8 开始节点的 `globals`（前端采用的形式）

`StartCell.Data` 另有 `globals` 字段（`List<KeyValueDto>`），前端控制台采用这一形式承载全局参数：

```json
{
  "cellType": "node_start",
  "data": {
    "inputsJsonSchema": "{\"type\":\"object\",\"properties\":{}}",
    "globals": [
      { "name": "类别", "key": "wftype", "type": "int", "value": 1 }
    ]
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `name` | 显示名，可读文本（如「类别」）|
| `key` | 引用键，表达式里使用的标识（如 `wftype`）|
| `type` | 类型，如 `int` / `string` |
| `value` | 值 |

> 与 4.7 的差异：`global_Data.staticParams` 的 `key` 等于 `name`，而本节形式的 `name` 与 `key` 是**两个独立字段**，可分别填写。前端写入 DSL 时只输出上述四个字段。
>
> 该结构取自 `hrh_dslDemo1.json` 中开始节点的实际内容。

## §5 Group（强组合 / 补偿组）

```json
{
  "groups": [
    { "id": "fhc4c6c8-...", "name": "hrh-http", "type": "group_compensate", "nodes": [] }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 组合 id |
| `name` | 组合名称 |
| `type` | 目前仅 `group_compensate`（补偿强组合）|
| `nodes` | 属于该组合的节点 id；**解析时由引擎从各节点的 `groupIds` 反向填充**，前端导出时可以为空 |

双向声明关系：
- 节点侧：`cell.groupIds = ["fhc4c6c8-..."]`
- 组合侧：引擎执行 `groupMap.get(gid).getNodes().add(cell.getId())`

**业务语义**：同组节点共享一份补偿上下文。组内某个节点失败时，引擎按逆序执行组内的补偿节点（通过 `edge_compensate` 挂接的补偿分支），实现「局部事务 + 人工/自动补偿」。

## §6 完整示例

### 6.1 最简流程（开始 → 数据映射 → HTTP → 结束）

见 `main/src/main/resources/hrh_http.json`。

### 6.2 带补偿的流程

见 `main/src/main/resources/hrh_dslDemo1.json`，要点：

- HTTP 节点 `groupIds` 指向补偿组；
- HTTP 节点的 `ports.items` 有三个桩点，分别接普通出线 / 补偿出线 / 其他出线；
- 补偿出线 `edge_compensate` → 补偿数据映射 `node_inner_compensateDatamap` → HTTP 补偿节点 `node_outer_httpCompensate`。

## §7 校验规则（前端需实现）

导出 DSL 前建议校验：

| 规则 | 说明 |
| --- | --- |
| R1 | 有且仅有一个 `node_start`、一个 `node_end` |
| R2 | 每条连线的 `source.cell` / `target.cell` 必须存在于 `cells` |
| R3 | 连线的 `source.port` / `target.port` 必须存在于对应节点的 `ports.items`（反之亦然：节点桩点被引用后不应删除） |
| R4 | 除开始节点外，每个节点至少有一条入线；除结束节点外，每个节点至少有一条出线 |
| R5 | 无孤立节点（无入线且无出线） |
| R6 | `node_start` 无入线，`node_end` 无出线 |
| R7 | 决策线 / 循环线的 `jsonpathElexpression` 非空 |
| R8 | `cell.groupIds` 中的 id 必须在 `groups` 中存在 |
| R9 | `inputsJsonSchema` / `outputsJsonSchema` 必须是合法 JSON 字符串 |
| R10 | 算子节点的 `data.opId` 必须能解析到已注册算子（缺失或已删除时报错，需重新绑定）|
| R11 | `#dynamicParams_$<key>` 引用必须在开始节点 `data.globals[].key` 中存在 |

> 引擎侧在 `DslParser` 中使用 `Optional.get()`（如第 33、139、152 行）提取开始/结束节点，若缺失会抛 `NoSuchElementException`，因此 R1 与 R5 属于强约束。

> R10 / R11 为前端新增：R10 对应算子绑定有效性，R11 对应 `DataMapOperateExecutor#valueConvert` L339-346 的动态参数取值（引用名不存在时该处会取不到值）。

---

## 📚 相关文档
- 💻 [前端控制台说明](06-前端控制台说明.md) — 本规范在前端的落地（节点库、属性面板、校验）
- ⚙️ [执行引擎与调度流程](03-执行引擎与调度流程.md) — 本规范如何被解析成可调度图
- 🗂️ [数据模型与消息模型](04-数据模型与消息模型.md) — DSL 落库后的 Inst 结构
- 🧩 [算子扩展指南](07-算子扩展指南.md) — 新增 cellType 的完整步骤
- 🧪 [测试指南](14-测试指南.md) §3 — 解析层验证用例
- 📏 [编码规范](16-编码规范.md) §1.2 — cellType 命名规范
