# service_arrange

基于 DSL 的服务编排平台。用一份可视化编排的 JSON（DSL）把多个分散的服务串成可执行流程，提供解析、调度执行、状态日志、失败补偿、循环与条件分支能力。

> 详细说明见 [`docs/`](./docs) 目录。

## 仓库结构

```
service_arrange/
├── docs/                       项目文档（本套文档）
│   ├── 01-项目总览.md
│   ├── 02-DSL规范.md
│   ├── 03-执行引擎与调度流程.md
│   ├── 04-数据模型与消息模型.md
│   ├── 05-REST接口说明.md
│   ├── 06-前端控制台说明.md
│   ├── 07-算子扩展指南.md
│   ├── 08-架构图与实现状态.md
│   └── 09-功能清单.md
├── frontend/                   可视化编排控制台（纯静态前端）
│   ├── index.html
│   ├── css/style.css
│   └── js/*.js
├── servicearrange/             后端（Maven 多模块）
│   ├── servicearrangeImpl/     核心引擎
│   └── main/                   Web 启动模块
└── 服务编排关键技术.docx        原始需求文档
```

## 快速开始

### 前端控制台

无需构建，直接用浏览器打开：

```
frontend/index.html
```

或启动一个静态服务器：

```powershell
cd frontend
npx serve .
# 或
python -m http.server 5173
```

打开后在右上角「接口设置」中填入后端地址（如 `http://localhost:8080`），点「连通性检测」确认。

### 后端

```powershell
cd servicearrange
mvn -pl main -am spring-boot:run
```

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [01-项目总览](./docs/01-项目总览.md) | 定位、模块结构、技术栈、架构总览、时序 |
| [02-DSL规范](./docs/02-DSL规范.md) | DSL 完整字段规范、cellType 取值、各节点 data、校验规则 |
| [03-执行引擎与调度流程](./docs/03-执行引擎与调度流程.md) | 解析、转换、算子路由、决策/循环/补偿算法 |
| [04-数据模型与消息模型](./docs/04-数据模型与消息模型.md) | Inst/Task/InstLog 字段、Mongo 集合、MQ 通道、ZK 锁 |
| [05-REST接口说明](./docs/05-REST接口说明.md) | 全部 HTTP 接口、参数与示例 |
| [06-前端控制台说明](./docs/06-前端控制台说明.md) | 前端能力、操作手册、接口契约、部署方式 |
| [07-算子扩展指南](./docs/07-算子扩展指南.md) | 新增 cellType / Execute / TaskInterface 的完整步骤 |
| [08-架构图与实现状态](./docs/08-架构图与实现状态.md) | 分层架构图、数据流图、状态机、实现状态对照表、缺陷清单 |
| [09-功能清单](./docs/09-功能清单.md) | 15 个功能域 204 项功能逐项状态、缺陷优先级、成熟度评估 |

## 核心概念

| 概念 | 说明 |
| --- | --- |
| **DSL** | 一份 JSON，描述流程的画布元素（节点 + 连线）与强组合 |
| **Cell** | 节点或连线的统称，两者同构 |
| **Inst** | 一次流程运行的上下文（解析后的图、全局参数、状态集合、出参）|
| **Task** | 一个节点在某个循环次数下的一次执行记录 |
| **决策线** | 条件线，条件成立才流转 |
| **循环线** | 圈定区间并定义循环退出条件（doWhile / whileDo）|
| **强组合 + 补偿线** | 节点失败后触发补偿分支，实现局部事务回滚 |
| **算子** | 最小执行单元（HTTP / Dubbo / WebService / 脚本 / 内部算子）|

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | 原生 HTML / CSS / JavaScript（零依赖、零构建）|
| 后端 | Spring Boot、Spring Cloud Stream |
| 消息 | RabbitMQ |
| 存储 | MongoDB |
| 协调 | ZooKeeper（实例级分布式锁）|
| 表达式 | Spring SpEL + JSONPath |
| 工具 | Hutool、Lombok |

## License

见 [LICENSE](./LICENSE)。
