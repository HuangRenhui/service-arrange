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

  /* ============ 算子（http / sql / shell） ============ */
  var OP_TYPES = {
    http:  { name: 'HTTP 接口', color: '#14b8a6', icon: '☁', desc: '调用 REST 接口' },
    sql:   { name: 'SQL 脚本',  color: '#8b5cf6', icon: '⛁', desc: '执行数据库 SQL' },
    shell: { name: 'Shell 脚本', color: '#f59e0b', icon: '$', desc: '执行本机脚本' }
  };

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
    var base = {
      id: '', opType: type || 'http', name: '', description: '',
      createTime: 0, updateTime: 0
    };
    if (type === 'sql') {
      base.database = '';          // 选择的数据库
      base.sql = '';               // 手写 SQL
    } else if (type === 'shell') {
      base.env = '';               // 执行环境
      base.script = '';            // 脚本内容
      base.timeout = 30000;
    } else {
      base.url = ''; base.method = 'GET';
      base.headers = []; base.query = []; base.body = '';
      base.contentType = 'application/json';
      base.timeout = 30000;
    }
    return base;
  }

  /* ============ 实例 ============ */
  function allInstances() { return readJSON(K.INSTANCES, {}); }

  function listInstances() {
    var all = allInstances();
    return Object.keys(all).map(function (k) { return all[k]; })
      .sort(function (a, b) { return (b.updateTime || 0) - (a.updateTime || 0); });
  }
  function getInstance(id) { return allInstances()[id] || null; }

  function saveInstance(inst) {
    var all = allInstances();
    if (!inst.id) inst.id = uid();
    inst.updateTime = Date.now();
    if (!inst.createTime) inst.createTime = inst.updateTime;
    if (!inst.cells) inst.cells = [];
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
    var cells = (dsl.cells || []).map(function (c) { return Object.assign({}, c); });
    var nodes = cells.filter(function (c) { return !DslValidator.isEdge(c.cellType); });
    var edges = cells.filter(function (c) { return DslValidator.isEdge(c.cellType); });
    layoutAuto(nodes, edges);
    return {
      cells: cells,
      groups: (dsl.groups || []).map(function (g) { return Object.assign({ nodes: [] }, g); }),
      dynamicGlobalParameters: dsl.dynamicGlobalParameters || []
    };
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
    OP_TYPES: OP_TYPES,

    // 算子
    allOperators: allOperators,
    listOperators: listOperators,
    getOperator: getOperator,
    saveOperator: saveOperator,
    removeOperators: removeOperators,
    newOperator: newOperator,

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
