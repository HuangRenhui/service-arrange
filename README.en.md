# service-arrange · Service Orchestration Platform

A DSL-based service orchestration platform. It chains distributed services into executable workflows through a single visualized JSON document (the DSL), and provides DSL parsing, scheduling and execution, state and logging, failure compensation, loops and conditional branching.

> Full documentation lives in [`docs/`](./docs). Start with [文档说明](./docs/文档说明.md).
> Note: the documentation set is currently maintained in Chinese.
> 中文版入口见 [`README.md`](./README.md)。

---

## UI Preview

**Orchestration Designer**: categorized node palette on the left (HTTP / SQL / Shell / internal operators); the canvas supports four edge types — normal, decision, loop and compensate — with draggable nodes and a grouped property inspector.

![Orchestration Designer](./docs/images/02-designer.png)

![Drag a node to rearrange](./docs/images/drag-node.gif)

**Run Detail**: one record per run, with the graph colored by result (success / fail / skip / running / not executed) and drill-down to per-operator logs.

![Run Detail · Graph](./docs/images/04-run-detail-graph.png)

<details>
<summary>More screenshots (instances / operator registry / schema tool / run inputs / failed compensation)</summary>

![Instances](./docs/images/01-instances.png)
![Operator Registry](./docs/images/06-operator.png)
![Schema Tool](./docs/images/07-schema.png)
![Run Inputs](./docs/images/08-run-inputs.png)
![Failed run · colored graph](./docs/images/10-run-detail-fail-graph.png)

</details>

---

## Repository Layout

```
service-arrange/
├── README.md                   Entry point and quick start (Chinese)
├── README.en.md                Entry point and quick start (English)
├── LICENSE
├── pom.xml                     Root POM (aggregates backend)
│
├── backend/                    Backend (Maven multi-module)
│   ├── pom.xml                 Backend aggregator POM
│   ├── engine/                 Core engine: DSL parsing, task scheduling, operator execution, persistence
│   └── web/                    Web layer: REST APIs and the bootstrap class
│
├── frontend/                   Visual orchestration console (pure static, zero dependency)
│   ├── index.html
│   ├── css/style.css
│   ├── js/*.js
│   └── serve.js                Local zero-dependency static server (Node built-ins only)
│
└── docs/                       Project documentation
    ├── 文档说明.md              Documentation index and conventions
    ├── 01-项目总览.md
    ├── 02-DSL规范.md
    ├── 03-执行引擎与调度流程.md
    ├── 04-数据模型与消息模型.md
    ├── 05-REST接口说明.md
    ├── 06-前端控制台说明.md
    ├── 07-算子扩展指南.md
    ├── 08-架构图与实现状态.md
    ├── 09-功能清单.md
    ├── 10-项目现状.md
    ├── 11-快速开始.md
    ├── 12-配置说明.md
    ├── 13-部署指南.md
    ├── 14-测试指南.md
    ├── 15-系统架构.md
    └── 16-编码规范.md
```

---

## Quick Start

### Frontend Console

No build step required — open it directly in a browser:

```
frontend/index.html
```

Or start the bundled zero-dependency static server (recommended):

```powershell
cd frontend
node serve.js      # listens on 127.0.0.1:5173 by default
```

Equivalent third-party static servers (optional, requires Node/npm or Python):

```powershell
npx serve .
python -m http.server 5173
```

After it opens, fill in the backend address in the top-right "API Settings" (**the context path is required**, e.g. `http://localhost:8082/servicearrange`) and click "Connectivity Check" to confirm.

### Backend

```powershell
cd backend
mvn -pl web -am spring-boot:run
```

> For the complete startup steps (including MongoDB / RabbitMQ / ZooKeeper preparation), see [快速开始](./docs/11-快速开始.md).

### Build Artifact

```powershell
cd backend
mvn clean package -DskipTests
```

Output: `backend/web/target/service-arrange.jar` (an executable Spring Boot jar).

---

## Core Concepts

| Concept | Description |
| --- | --- |
| **DSL** | A single JSON document describing the canvas elements (nodes + edges) and strong groups of a workflow |
| **Cell** | Generic name for a node or an edge; both are structurally identical |
| **Inst** | The context of one workflow run (parsed graph, global parameters, node state sets, outputs) |
| **Task** | One execution record of a node under a given loop iteration |
| **Decision edge** | A conditional edge; the flow continues only when the condition holds |
| **Loop edge** | Defines a loop region and its exit condition (`doWhile` / `whileDo`) |
| **Strong group + compensate edge** | Triggers the compensation branch on node failure, achieving partial transaction rollback |
| **Operator** | The smallest executable unit (HTTP / Dubbo / WebService / script / internal operator) |

---

## Tech Stack

| Layer | Technology |
| --- | --- |
| Frontend | Vanilla HTML5 / CSS3 / ES5 JavaScript (zero dependency, zero build) |
| Backend | Java 11, Spring Boot 2.1.5, Spring Cloud Stream |
| Messaging | RabbitMQ |
| Storage | MongoDB |
| Coordination | ZooKeeper + Curator (instance-level distributed lock) |
| Expression | Spring SpEL + custom value expressions |
| Utilities | Hutool, Lombok |

---

## Documentation Navigation

| Category | Documents |
| --- | --- |
| **Getting started** | [文档说明](./docs/文档说明.md) · [快速开始](./docs/11-快速开始.md) · [项目总览](./docs/01-项目总览.md) |
| **Product & status** | [项目现状](./docs/10-项目现状.md) · [功能清单](./docs/09-功能清单.md) |
| **Design & architecture** | [系统架构](./docs/15-系统架构.md) · [DSL规范](./docs/02-DSL规范.md) · [执行引擎与调度流程](./docs/03-执行引擎与调度流程.md) |
| **Development & APIs** | [REST接口说明](./docs/05-REST接口说明.md) · [算子扩展指南](./docs/07-算子扩展指南.md) · [前端控制台说明](./docs/06-前端控制台说明.md) · [编码规范](./docs/16-编码规范.md) |
| **Data & operations** | [数据模型与消息模型](./docs/04-数据模型与消息模型.md) · [配置说明](./docs/12-配置说明.md) · [部署指南](./docs/13-部署指南.md) · [测试指南](./docs/14-测试指南.md) · [架构图与实现状态](./docs/08-架构图与实现状态.md) |

---

## License

See [LICENSE](./LICENSE).
