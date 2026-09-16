/* ===== 数据存储层 =====
 * 职责：
 *   1. 实例（Instance）—— 编排的主体，一个实例 = 一个流程
 *   2. 运行记录（RunRecord）—— 每次运行的快照与状态
 *   3. 算子（Operator）—— 已注册的算子（http / sql / shell）
 *   4. 日志（Log）—— 实例级 + 算子级两级日志
 *
 * 存储策略：localStorage 兜底（后端接口就绪后由 api.js 的远程分支接管）。
 * DSL 序列化沿用原有 toDSL / fromDSL，与后端契约保持一致。
 */
(function (global) {
  'use strict';

  var K = {
    INSTANCES: 'sa.instances',      // { [instId]: Instance }
    RUNS: 'sa.runs',                // { [instId]: RunRecord[] }
    OPERATORS: 'sa.operators',      // { [opId]: Operator }
    CURRENT: 'sa.currentInstance',  // 当前打开的实例 id
    SETTINGS: 'sa.settings'
  };

  /* ============ 基础读写 ============ */
  function readJSON(key, fallback) {
    try {
      var raw = localStorage.getItem(key);
      if (!raw) return fallback;
      var v = JSON.parse(raw);
      return v === null || v === undefined ? fallback : v;
    } catch (e) { return fallback; }
  }
  function writeJSON(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); return true; }
    catch (e) { return false; }
  }
  function uid() {
    if (global.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      var r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
  }
  function now() { return new Date().toISOString(); }
  /** 数值兜底：非有限数（undefined/NaN/null）一律回落为默认值 */
  function num(v, dft) {
    v = Number(v);
    return isFinite(v) ? v : dft;
  }

  /* ============ 算子类型 ============
   * 三类外部算子（用户注册）：http / sql / shell
   * 其余为引擎内置算子（按 resources/problem.txt 与 DSL 示例定义），
   * 同样以「算子」形式注册，可编辑、可拖入编排。
   */
  var OP_TYPES = {
    /* —— 用户注册的外部算子 —— */
    http:  { name: 'HTTP 接口', color: '#14b8a6', icon: '☁', desc: '调用 REST 接口', group: 'outer' },
    sql:   { name: 'SQL 脚本',  color: '#8b5cf6', icon: '⛁', desc: '执行数据库 SQL', group: 'outer' },
    shell: { name: 'Shell 脚本', color: '#f59e0b', icon: '$', desc: '执行本机脚本', group: 'outer' },

    /* —— 引擎内置算子（对应 CellType） —— */
    datamap:      { name: '数据映射',     color: '#8b5cf6', icon: '⇄', desc: '把上游输出映射为下游入参', group: 'inner', cellType: 'node_inner_datamap' },
    transform2obj: { name: '规则转换',     color: '#8b5cf6', icon: '⇉', desc: '按 transformRules 重组 JSON', group: 'inner', cellType: 'node_inner_transform2Obj' },
    dataresult:   { name: '数据结果',     color: '#8b5cf6', icon: '▦', desc: '整理算子输出作为结果集', group: 'inner', cellType: 'node_inner_dataresult' },
    sleep:        { name: '延时',         color: '#8b5cf6', icon: '⏱', desc: '暂停指定毫秒', group: 'inner', cellType: 'node_inner_sleep' },
    arrayExtract: { name: '数组提取',     color: '#0ea5e9', icon: '[ ]', desc: '从数组中按条件提取元素', group: 'inner', cellType: 'node_inner_arrayExtract' },
    arrayAgg:     { name: '数组聚合',     color: '#0ea5e9', icon: 'Σ', desc: '对数组元素做聚合计算', group: 'inner', cellType: 'node_inner_arrayAgg' },
    obj2Array:    { name: '对象封装为数组', color: '#0ea5e9', icon: '⊞', desc: '把单个对象封装成数组', group: 'inner', cellType: 'node_inner_obj2Array' },
    replace:      { name: '替换',         color: '#0ea5e9', icon: '⇋', desc: '按规则替换字段值', group: 'inner', cellType: 'node_inner_replace' },
    defaultRule:  { name: '默认规则',     color: '#0ea5e9', icon: '◎', desc: '字段缺省时填充默认值', group: 'inner', cellType: 'node_inner_defaultRule' },
    listAgg:      { name: 'list 聚合',    color: '#0ea5e9', icon: '≡', desc: '把多个 list 合并为一个', group: 'inner', cellType: 'node_inner_listAgg' },
    convertJson2Xml: { name: 'JSON 转 XML', color: '#0ea5e9', icon: '⇰', desc: '报文格式转换', group: 'inner', cellType: 'node_inner_convertJson2Xml' },
    convertXml2Json: { name: 'XML 转 JSON', color: '#0ea5e9', icon: '⇱', desc: '报文格式转换', group: 'inner', cellType: 'node_inner_convertXml2Json' },
    execShell:    { name: 'Shell 执行',   color: '#f59e0b', icon: '$', desc: '引擎内执行 shell', group: 'inner', cellType: 'node_inner_execShell' },
    execJar:      { name: 'Jar 执行',     color: '#f59e0b', icon: '⬢', desc: '执行 jar 包', group: 'inner', cellType: 'node_inner_execJar' },
    execPython:   { name: 'Python 执行',  color: '#f59e0b', icon: 'π', desc: '执行 python 脚本', group: 'inner', cellType: 'node_inner_execPython' },

    /* —— 补偿算子 —— */
    httpCompensate:      { name: 'HTTP 补偿',      color: '#ef4444', icon: '⤺', desc: 'HTTP 调用的补偿动作', group: 'compensate', cellType: 'node_outer_httpCompensate' },
    dubboCompensate:     { name: 'Dubbo 补偿',     color: '#ef4444', icon: '⤺', desc: 'Dubbo 调用的补偿动作', group: 'compensate', cellType: 'node_outer_dubboCompensate' },
    webserviceCompensate:{ name: 'WebService 补偿', color: '#ef4444', icon: '⤺', desc: 'WebService 调用的补偿动作', group: 'compensate', cellType: 'node_outer_webserviceCompensate' },
    compensateDatamap:   { name: '补偿数据映射',   color: '#ef4444', icon: '⇄', desc: '为补偿算子组装入参', group: 'compensate', cellType: 'node_inner_compensateDatamap' },

    /* —— 连线算子（仅作定义，调色板不展示） —— */
    edge_common:   { name: '普通线',   color: '#64748b', icon: '→', desc: '顺序流转', group: 'edge', cellType: 'edge_common' },
    edge_decision: { name: '决策线',   color: '#64748b', icon: '◇', desc: '条件成立才流转', group: 'edge', cellType: 'edge_decision' },
    edge_loop:     { name: '循环线',   color: '#64748b', icon: '↻', desc: '圈定循环区间', group: 'edge', cellType: 'edge_loop' },
    edge_compensate: { name: '补偿线', color: '#64748b', icon: '⟲', desc: '失败时转入补偿分支', group: 'edge', cellType: 'edge_compensate' }
  };

  /** 用户可在「算子注册」页选择的类型（外部算子） */
  var REGISTERABLE_TYPES = ['http', 'sql', 'shell'];

  /** 按分组取类型列表 */
  function opTypesByGroup(group) {
    return Object.keys(OP_TYPES).filter(function (k) {
      return OP_TYPES[k].group === group;
    });
  }

  function allOperators() { return readJSON(K.OPERATORS, {}); }
  function listOperators(type) {
    var all = allOperators();
    return Object.keys(all).map(function (k) { return all[k]; })
      .filter(function (o) { return !type || o.opType === type; })
      .sort(function (a, b) { return (b.updateTime || 0) - (a.updateTime || 0); });
  }
  function getOperator(id) { return allOperators()[id] || null; }
  function saveOperator(op) {
    var all = allOperators();
    if (!op.id) op.id = uid();
    op.updateTime = Date.now();
    if (!op.createTime) op.createTime = op.updateTime;
    all[op.id] = op;
    writeJSON(K.OPERATORS, all);
    return op;
  }
  function removeOperators(ids) {
    var all = allOperators();
    (ids || []).forEach(function (id) { delete all[id]; });
    writeJSON(K.OPERATORS, all);
  }

  /** 新建算子的默认模板（按类型） */
  function newOperator(type) {
    type = type || 'http';
    var base = {
      id: '', opType: type, name: '', description: '',
      createTime: 0, updateTime: 0
    };
    if (type === 'sql') {
      base.database = '';          // 选择的数据库
      base.sql = '';               // 手写 SQL
      base.timeout = 30000;
    } else if (type === 'shell') {
      base.env = '';               // 执行环境
      base.script = '';            // 脚本内容
      base.timeout = 30000;
    } else if (type === 'http') {
      base.url = ''; base.method = 'GET';
      base.headers = []; base.query = []; base.body = '';
      base.contentType = 'application/json';
      base.timeout = 30000;
    } else if (type === 'datamap' || type === 'compensateDatamap') {
      base.childInputs = { reqPath: [], reqQuery: [], reqHeaders: [], reqBodyForm: [], reqBodyOther: '' };
    } else if (type === 'transform2obj') {
      base.transformRules = [];
    } else if (type === 'dataresult') {
      base.resultRules = [];
    } else if (type === 'sleep') {
      base.milliseconds = 1000;
    } else if (type === 'arrayExtract') {
      base.arrayJsonpath = ''; base.idxSelectName = ''; base.selecFiled = []; base.conditionData = [];
    } else if (type === 'arrayAgg') {
      base.arrayJsonpath = ''; base.aggField = ''; base.aggType = 'sum';
    } else if (type === 'obj2Array') {
      base.objJsonpath = ''; base.arrayName = '';
    } else if (type === 'replace') {
      base.replaceRules = [];
    } else if (type === 'defaultRule') {
      base.defaultRules = [];
    } else if (type === 'listAgg') {
      base.listJsonpaths = []; base.aggName = '';
    } else if (type === 'convertJson2Xml' || type === 'convertXml2Json') {
      base.sourceJsonpath = ''; base.targetKey = ''; base.rootName = '';
    } else if (type === 'execShell') {
      base.script = ''; base.env = ''; base.timeout = 30000;
    } else if (type === 'execJar') {
      base.jarPath = ''; base.args = []; base.timeout = 30000;
    } else if (type === 'execPython') {
      base.script = ''; base.interpreter = 'python3'; base.timeout = 30000;
    } else if (type === 'httpCompensate') {
      base.url = ''; base.method = 'POST'; base.headers = []; base.body = '';
    } else if (type === 'dubboCompensate') {
      base.interfaceName = ''; base.methodName = ''; base.paramTypes = []; base.params = [];
    } else if (type === 'webserviceCompensate') {
      base.wsdl = ''; base.operation = ''; base.params = [];
    }
    return base;
  }

  /* ============ 内置算子播种 ============
   * 把 resources 中定义的内置算子预置为「已注册算子」，
   * 使用户可在编排页拖拽、在算子注册页编辑。
   * 已存在（含用户删改过的）不覆盖，仅补齐缺失项。
   */
  var SEEDED_FLAG = 'se_arr_op_seeded_v1';

  function seedBuiltinOperators() {
    if (readJSON(SEEDED_FLAG, false)) return 0;
    var all = allOperators();
    var added = 0;
    Object.keys(OP_TYPES).forEach(function (t) {
      var meta = OP_TYPES[t];
      if (meta.group === 'edge') return;              // 连线不作为算子
      if (REGISTERABLE_TYPES.indexOf(t) >= 0) return;  // 外部算子由用户自行注册
      var id = 'builtin_' + t;
      if (all[id]) return;
      var op = newOperator(t);
      op.id = id;
      op.name = meta.name;
      op.description = meta.desc;
      op.builtin = true;
      op.group = meta.group;
      op.createTime = Date.now();
      op.updateTime = op.createTime;
      all[id] = op;
      added++;
    });
    writeJSON(K.OPERATORS, all);
    writeJSON(SEEDED_FLAG, true);
    return added;
  }

  /* ============ 实例 ============ */
  function allInstances() { return readJSON(K.INSTANCES, {}); }

  function listInstances() {
    var all = allInstances();
    return Object.keys(all).map(function (k) { return all[k]; })
      .sort(function (a, b) { return (b.updateTime || 0) - (a.updateTime || 0); });
  }
  /** 读取实例，并顺带修复历史脏数据（无效连线等） */
  function getInstance(id) {
    var inst = allInstances()[id];
    if (!inst) return null;
    if (inst.cells) inst.cells = sanitizeCells(inst.cells);
    return inst;
  }

  function saveInstance(inst) {
    var all = allInstances();
    if (!inst.id) inst.id = uid();
    inst.updateTime = Date.now();
    if (!inst.createTime) inst.createTime = inst.updateTime;
    /* 写入前清洗，避免脏数据被持久化 */
    inst.cells = sanitizeCells(inst.cells || []);
    if (!inst.groups) inst.groups = [];
    all[inst.id] = inst;
    writeJSON(K.INSTANCES, all);
    return inst;
  }

  function createInstance(name, description) {
    var id = uid();
    var model = Store.createBlank();
    var inst = {
      id: id,
      name: name || ('未命名实例 ' + id.slice(0, 4)),
      description: description || '',
      planId: 'PLAN_' + id.slice(0, 8).toUpperCase(),
      cells: model.cells,
      groups: [],
      createTime: 0,
      updateTime: 0,
      lastRunState: '',       // 最近一次运行状态
      runCount: 0
    };
    return saveInstance(inst);
  }

  function removeInstance(id) {
    var all = allInstances();
    delete all[id];
    writeJSON(K.INSTANCES, all);
    var runs = readJSON(K.RUNS, {});
    delete runs[id];
    writeJSON(K.RUNS, runs);
  }

  function currentInstanceId() { return localStorage.getItem(K.CURRENT) || ''; }
  function setCurrentInstanceId(id) { try { localStorage.setItem(K.CURRENT, id); } catch (e) {} }

  /* ============ 运行记录 ============ */
  function allRuns() { return readJSON(K.RUNS, {}); }

  function listRuns(instId) {
    var m = allRuns()[instId] || [];
    return m.slice().sort(function (a, b) { return (b.seq || 0) - (a.seq || 0); });
  }
  function getRun(instId, runId) {
    return (allRuns()[instId] || []).filter(function (r) { return r.id === runId; })[0] || null;
  }

  /** 新建一条运行记录 */
  function createRun(instId, extra) {
    var m = allRuns();
    var arr = m[instId] || [];
    var run = {
      id: uid(),
      instId: instId,
      seq: arr.length + 1,
      state: 'RUNNING',
      startTime: now(),
      endTime: '',
      duration: 0,
      inputs: (extra && extra.inputs) || {},
      outputs: null,
      cells: (extra && extra.cells) || [],     // 运行时的编排快照
      groups: (extra && extra.groups) || [],
      nodeStates: {},                          // { cellId: {state, startTime, endTime, duration} }
      logs: [],                                // 实例级日志
      nodeLogs: {},                            // { cellId: [ {time, level, message, inputs, outputs} ] }
      mock: true
    };
    arr.push(run);
    m[instId] = arr;
    writeJSON(K.RUNS, m);
    return run;
  }

  function saveRun(run) {
    var m = allRuns();
    var arr = m[run.instId] || [];
    var idx = -1;
    for (var i = 0; i < arr.length; i++) { if (arr[i].id === run.id) { idx = i; break; } }
    if (idx >= 0) arr[idx] = run; else arr.push(run);
    m[run.instId] = arr;
    writeJSON(K.RUNS, m);
    return run;
  }

  function removeRun(instId, runId) {
    var m = allRuns();
    m[instId] = (m[instId] || []).filter(function (r) { return r.id !== runId; });
    writeJSON(K.RUNS, m);
  }

  /* ============ 接口设置 ============ */
  function getSettings() { return readJSON(K.SETTINGS, { apiBase: '' }); }
  function setSettings(s) { writeJSON(K.SETTINGS, s); }

  /* ============ DSL 序列化（与后端契约一致） ============ */
  function toDSL(model) {
    var cells = (model.cells || []).map(function (c) {
      var cell = {
        id: c.id,
        name: c.name || (c.cellType + '_' + c.id),
        cellType: c.cellType,
        data: c.data || {}
      };
      if (DslValidator.isEdge(c.cellType)) {
        cell.source = c.source;
        cell.target = c.target;
      } else {
        /* 画布坐标必须随 DSL 一起保存，否则每次 persist 后重开会丢失布局，
           节点会全部堆到左上角，并让 fit() 算出 NaN 缩放。 */
        cell.x = num(c.x, 0);
        cell.y = num(c.y, 0);
        cell.ports = c.ports || { items: [{ id: c.id + '_out' }, { id: c.id + '_in' }] };
        if (c.groupIds && c.groupIds.length) cell.groupIds = c.groupIds.slice();
      }
      return cell;
    });

    var groups = (model.groups || []).map(function (g) {
      return { id: g.id, name: g.name, type: g.type || 'group_compensate', nodes: [] };
    });
    cells.forEach(function (c) {
      (c.groupIds || []).forEach(function (gid) {
        var g = groups.filter(function (x) { return x.id === gid; })[0];
        if (g && g.nodes.indexOf(c.id) < 0) g.nodes.push(c.id);
      });
    });

    return {
      cells: cells,
      groups: groups,
      dynamicGlobalParameters: model.dynamicGlobalParameters || []
    };
  }

  function fromDSL(dsl) {
    var cells = sanitizeCells((dsl.cells || []).map(function (c) { return Object.assign({}, c); }));
    var nodes = cells.filter(function (c) { return !DslValidator.isEdge(c.cellType); });
    var edges = cells.filter(function (c) { return DslValidator.isEdge(c.cellType); });
    layoutAuto(nodes, edges);
    return {
      cells: cells,
      groups: (dsl.groups || []).map(function (g) { return Object.assign({ nodes: [] }, g); }),
      dynamicGlobalParameters: dsl.dynamicGlobalParameters || []
    };
  }

  /**
   * 修复历史遗留的脏数据。
   *
   * 早期版本存在缺陷：调色板中的「连线」被当作普通节点拖入画布，
   * 生成了没有 source/target 的孤立连线（在画布上表现为一个方块）。
   * 这里在读取时自动剔除这类无效元素，避免用户手工清理。
   */
  function sanitizeCells(cells) {
    return (cells || []).filter(function (c) {
      if (!c || !c.cellType) return false;
      if (DslValidator.isEdge(c.cellType)) {
        var okSrc = c.source && c.source.cell;
        var okTgt = c.target && c.target.cell;
        return !!(okSrc && okTgt);
      }
      return true;
    });
  }

  function layoutAuto(nodes, edges) {
    var hasPos = nodes.filter(function (n) { return typeof n.x === 'number'; });
    if (hasPos.length === nodes.length && nodes.length > 1) return;

    var indeg = {}, children = {};
    nodes.forEach(function (n) { indeg[n.id] = 0; children[n.id] = []; });
    edges.forEach(function (e) {
      var s = e.source && e.source.cell, t = e.target && e.target.cell;
      if (indeg[t] !== undefined) indeg[t]++;
      if (children[s]) children[s].push(t);
    });
    var level = {}, queue = [];
    nodes.forEach(function (n) { if (indeg[n.id] === 0) { level[n.id] = 0; queue.push(n.id); } });
    if (!queue.length && nodes.length) { level[nodes[0].id] = 0; queue.push(nodes[0].id); }
    while (queue.length) {
      var cur = queue.shift();
      (children[cur] || []).forEach(function (c) {
        var lv = (level[cur] || 0) + 1;
        if (level[c] === undefined || lv > level[c]) level[c] = lv;
        indeg[c]--;
        if (indeg[c] <= 0) queue.push(c);
      });
    }
    var countAt = {};
    nodes.forEach(function (n) {
      var lv = level[n.id] === undefined ? 0 : level[n.id];
      countAt[lv] = (countAt[lv] || 0) + 1;
      n.x = 80 + lv * 250;
      n.y = 90 + (countAt[lv] - 1) * 140;
    });
  }

  /** 空白编排（含默认首尾节点） */
  function createBlank() {
    var startId = uid(), endId = uid();
    return {
      cells: [
        Object.assign({}, NodeDefs.get('node_start').defaultData(), {
          id: startId, cellType: 'node_start', name: '开始', x: 80, y: 200,
          ports: { items: [{ id: startId + '_out' }, { id: startId + '_in' }] }
        }),
        Object.assign({}, NodeDefs.get('node_end').defaultData(), {
          id: endId, cellType: 'node_end', name: '结束', x: 800, y: 200,
          ports: { items: [{ id: endId + '_out' }, { id: endId + '_in' }] }
        })
      ],
      groups: [],
      dynamicGlobalParameters: []
    };
  }

  /* ============ 导出 ============ */
  var Store = {
    uid: uid,
    now: now,
    num: num,
    OP_TYPES: OP_TYPES,
    REGISTERABLE_TYPES: REGISTERABLE_TYPES,
    opTypesByGroup: opTypesByGroup,

    // 算子
    allOperators: allOperators,
    listOperators: listOperators,
    getOperator: getOperator,
    saveOperator: saveOperator,
    removeOperators: removeOperators,
    newOperator: newOperator,
    seedBuiltinOperators: seedBuiltinOperators,

    // 实例
    listInstances: listInstances,
    getInstance: getInstance,
    saveInstance: saveInstance,
    createInstance: createInstance,
    removeInstance: removeInstance,
    currentInstanceId: currentInstanceId,
    setCurrentInstanceId: setCurrentInstanceId,

    // 运行记录
    listRuns: listRuns,
    getRun: getRun,
    createRun: createRun,
    saveRun: saveRun,
    removeRun: removeRun,

    // 设置
    getSettings: getSettings,
    setSettings: setSettings,

    // DSL
    toDSL: toDSL,
    fromDSL: fromDSL,
    createBlank: createBlank
  };

  global.Store = Store;
})(window);
