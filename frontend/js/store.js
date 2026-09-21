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
    OPERATORS: 'sa.operators',      // 复用算子 { [opId]: Operator }（跨实例可见）
    CURRENT: 'sa.currentInstance',  // 当前打开的实例 id
    SETTINGS: 'sa.settings',
    ENVS: 'sa.envs'                 // 环境配置（shell 执行环境 / sql 数据库）
  };

  /* ============ 算子作用域 ============
   * shared  复用算子 —— 跨实例可见，存在 K.OPERATORS
   * local   临时算子 —— 只在所属实例可见，存在 instance.localOperators
   * builtin 内置算子 —— 系统预置，不可注册为复用
   */
  var OP_SCOPE = { SHARED: 'shared', LOCAL: 'local', BUILTIN: 'builtin' };

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
    /* —— 用户注册的外部算子 ——
       opType 取值即后端 cellType（见 dslDemo.json：node_outer_http / node_outer_dubbo /
       node_outer_webservice）。sql / shell 后端暂无对应 cellType，沿用 node_outer_ 前缀
       以便与后端命名规约一致。 */
    http:  { name: 'HTTP 接口', color: '#14b8a6', icon: '☁', desc: '调用 REST 接口', group: 'outer' },
    sql:   { name: 'SQL 脚本',  color: '#8b5cf6', icon: '⛁', desc: '执行数据库 SQL', group: 'outer' },
    shell: { name: 'Shell 脚本', color: '#f59e0b', icon: '$', desc: '执行本机脚本', group: 'outer' },

    /* —— 引擎内置算子（对应 CellType） ——
       仅保留 DSL 样本中真实出现过的类型：
         node_inner_datamap / node_inner_transform2Obj / node_inner_dataresult /
         node_inner_decision / node_inner_execPython
       其余（sleep / arrayExtract / arrayAgg / obj2Array / replace / defaultRule /
       listAgg / convertJson2Xml / convertXml2Json / execShell / execJar）
       样本中从未出现，属后端预留能力，按「不留无依据定义」原则移除。 */
    datamap:      { name: '数据映射',     color: '#8b5cf6', icon: '⇄', desc: '把上游输出映射为下游入参', group: 'inner', cellType: 'node_inner_datamap' },
    transform2obj: { name: '规则转换',     color: '#8b5cf6', icon: '⇉', desc: '按 transformRules 重组 JSON', group: 'inner', cellType: 'node_inner_transform2Obj' },
    dataresult:   { name: '数据结果',     color: '#8b5cf6', icon: '▦', desc: '整理算子输出作为结果集', group: 'inner', cellType: 'node_inner_dataresult' },
    decision:     { name: '决策',         color: '#8b5cf6', icon: '◇', desc: '按条件判定分支', group: 'inner', cellType: 'node_inner_decision' },
    execPython:   { name: 'Python 执行',  color: '#f59e0b', icon: 'π', desc: '执行 python 脚本', group: 'inner', cellType: 'node_inner_execPython' },

    /* —— 补偿算子 ——
       三者同属「补偿算子」一个分组：样本中它们常在同一条补偿链路上串联，
       例如 dslDemo.json：edge_compensate → node_inner_compensateDatamap
        → edge_common → node_outer_httpCompensate。
       对使用者而言都是「补偿动作」，故不按 inner / outer 拆分展示；
       cellType 仍保留样本中的完整值（node_inner_ / node_outer_ 前缀）以便后端路由。
       注：样本中不存在 dubboCompensate（后端仅有类型常量，无实际使用），故不提供。 */
    compensateDatamap:    { name: '补偿数据映射',    color: '#ef4444', icon: '⇄', desc: '为补偿算子组装入参', group: 'compensate', cellType: 'node_inner_compensateDatamap' },
    httpCompensate:       { name: 'HTTP 补偿',       color: '#ef4444', icon: '⤺', desc: 'HTTP 调用的补偿动作', group: 'compensate', cellType: 'node_outer_httpCompensate' },
    webserviceCompensate: { name: 'WebService 补偿', color: '#ef4444', icon: '⤺', desc: 'WebService 调用的补偿动作', group: 'compensate', cellType: 'node_outer_webserviceCompensate' },

    /* —— 连线算子（仅作定义，调色板不展示） —— */
    edge_common:   { name: '普通线',   color: '#64748b', icon: '→', desc: '顺序流转', group: 'edge', cellType: 'edge_common' },
    edge_decision: { name: '决策线',   color: '#64748b', icon: '◇', desc: '条件成立才流转', group: 'edge', cellType: 'edge_decision' },
    edge_loop:     { name: '循环线',   color: '#64748b', icon: '↻', desc: '圈定循环区间', group: 'edge', cellType: 'edge_loop' },
    edge_compensate: { name: '补偿线', color: '#64748b', icon: '⟲', desc: '失败时转入补偿分支', group: 'edge', cellType: 'edge_compensate' }
  };

  /** 用户可在「算子注册」页选择的类型（外部算子） */
  var REGISTERABLE_TYPES = ['http', 'sql', 'shell'];

  /* ============ 补偿算子与基础类型的关系 ============
   * 依据 DSL 样本（dslDemo.json / hrh_dslDemo1.json）：
   *   node_outer_httpCompensate 的 data 结构与 node_outer_http 逐字段一致；
   *   node_inner_compensateDatamap 与 node_inner_datamap 同理。
   * 即补偿版是同族基础算子的副本，差异仅在 cellType 与触发连线（edge_compensate）。
   * 因此字段定义不重复维护：这里只记录它与哪个基础类型同构，
   * schema / defaultData / 字段绑定一律复用基础类型，避免两处定义漂移。
   *
   * 未列出 dubboCompensate：样本中不存在该 cellType（后端仅有类型常量，无实际使用）。
   */
  var COMPENSATE_OF = {
    httpCompensate: 'http',
    webserviceCompensate: 'webservice',
    compensateDatamap: 'datamap'
  };

  /** 是否为补偿算子类型 */
  function isCompensateType(t) { return !!COMPENSATE_OF[t]; }

  /** 是否为补偿算子分组 */
  function isCompensateGroup(g) { return g === 'compensate'; }

  /** 基础（非补偿）类型：补偿算子映射到同族外部算子，其余原样返回 */
  function baseOpType(t) { return COMPENSATE_OF[t] || t; }

  /** 由基础类型推导补偿类型；无补偿变体时返回 null */
  function compensateTypeOf(t) {
    for (var k in COMPENSATE_OF) {
      if (COMPENSATE_OF[k] === t) return k;
    }
    return null;
  }

  /** 按分组取类型列表 */
  function opTypesByGroup(group) {
    return Object.keys(OP_TYPES).filter(function (k) {
      return OP_TYPES[k].group === group;
    });
  }

  /* ============ 内置算子的配置表单 ============
   * 字段名严格对应后端 DSL（取自 hrh_*.json / problem.txt 的真实结构），
   * 每种内置算子在属性面板中渲染成对应的输入框 / 选择框 / 规则表格。
   *
   * 字段类型：
   *   text / textarea / number / select / checkbox
   *   rules    —— 规则表格（可增删行）
   *   lines    —— 多行文本，每行一项，存为数组
   *   kvtable  —— key/value 两列表格
   *   json     —— JSON 文本框
   */
  /* 数据映射的参数行结构（取自 hrh_datamap.json / hrh_dslDemo2.json）：
       { name, key, type, jsonpathMapping, defaultValue }
     reqPath / reqQuery 在样本中多为 null，但结构一致，按同一套列渲染。 */
  var DATAMAP_RULE_COLS = ['name', 'key', 'type', 'jsonpathMapping', 'defaultValue'];
  var DATAMAP_RULE_TYPES = { type: ['Text', 'File', 'Number', 'Boolean', 'Array', 'Object'] };

  /* 数据映射的字段与后端 FunDatamapCell.Data.ChildInput 严格对应：
       reqPath / reqQuery / reqHeaders / reqBodyForm  → List<KeyValueWithJsonPathDto>
       reqBodyOther                                   → String（JSON Schema）
     注意：后端没有 reqBodyType 字段，不要凭 DSL 样本臆造。
     字段标签用「映射」语义，以区别于 HTTP 外部算子的「接口配置」。 */
  var OP_SCHEMAS = {
    /* —— 数据映射：把上游输出按规则组装成下游算子的入参 —— */
    datamap: [
      { key: 'parentsOutputs', label: '上游输出声明', type: 'rules',
        cols: ['contentType', 'outputsJsonSchema'],
        hint: '声明上游节点输出的内容类型与 JSON Schema，供映射规则引用' },
      { key: 'childInputs.reqPath', label: 'Path 入参映射', type: 'rules',
        cols: DATAMAP_RULE_COLS, colTypes: DATAMAP_RULE_TYPES,
        hint: '把上游输出映射为下游的 Path 参数：取值表达式写 #节点id$jsonpath' },
      { key: 'childInputs.reqQuery', label: 'Query 入参映射', type: 'rules',
        cols: DATAMAP_RULE_COLS, colTypes: DATAMAP_RULE_TYPES },
      { key: 'childInputs.reqHeaders', label: 'Header 入参映射', type: 'rules',
        cols: DATAMAP_RULE_COLS, colTypes: DATAMAP_RULE_TYPES },
      { key: 'childInputs.reqBodyForm', label: '表单入参映射', type: 'rules',
        cols: DATAMAP_RULE_COLS, colTypes: DATAMAP_RULE_TYPES },
      { key: 'childInputs.reqBodyOther', label: '请求体 Schema 映射', type: 'json',
        hint: 'JSON Schema；字段内用 jsonpathMapping 指定取值来源，用 defaultValue 兜底' }
    ],
    /* compensateDatamap 不单独定义：它是 datamap 的副本，
       schema 由 opSchema() 归一后复用上面这份。 */

    /* —— 规则转换：把上游结果重组成新对象 —— */
    transform2obj: [
      { key: 'transformRules', label: '转换规则', type: 'rules',
        cols: ['key', 'type', 'jsonpathMapping', 'inputValue', 'idx'],
        colTypes: { type: ['getLength', 'aryDeal', 'default', 'jsonpath'] },
        hint: 'key 目标字段 / type 规则类型 / jsonpathMapping 取值表达式 / inputValue 输入名 / idx 数组下标引用' }
    ],

    /* —— 数据结果：整理输出结果集 —— */
    dataresult: [
      { key: 'resultRules', label: '结果规则', type: 'rules',
        cols: ['key', 'jsonpathMapping', 'type'],
        hint: '声明最终输出给结束节点的字段' }
    ],

    /* —— 决策：按条件判定分支（样本见 hrh_httpdecision.json 的 node_inner_decision） —— */
    decision: [
      { key: 'conditionData', label: '决策条件', type: 'rules',
        cols: ['Ljsonpath', 'condition', 'Rjsonpath'],
        colTypes: { condition: ['==', '!=', '>', '>=', '<', '<=', 'contains', 'notNull'] },
        hint: '逐行判断，用于决定走哪条分支' }
    ],

    /* —— 脚本类执行算子 —— */
    execPython: [
      { key: 'interpreter', label: '解释器', type: 'select',
        options: ['python3', 'python', 'python2'] },
      { key: 'script', label: '脚本内容', type: 'textarea' },
      { key: 'timeout', label: '超时（毫秒）', type: 'number' }
    ],

    /* —— 补偿算子 —— */

    /* 补偿算子不在此处定义字段：它是同族外部算子的副本。
       httpCompensate / dubboCompensate / webserviceCompensate / compensateDatamap
       的 schema 由 opSchema() 剥掉 Compensate 后缀后复用基础类型的定义，
       确保「补偿 HTTP」与「HTTP」的字段永远一致。 */
  };

  /**
   * 取算子的配置表单定义。
   * 补偿算子没有独立定义，回落到同族基础类型（外部算子返回空数组，
   * 其表单由 designer.js 的 renderOuterOpForm 单独渲染）。
   */
  function opSchema(opType) {
    if (OP_SCHEMAS[opType]) return OP_SCHEMAS[opType];
    var b = baseOpType(opType);
    return (b !== opType && OP_SCHEMAS[b]) || [];
  }

  /* 规则表格的列名中文映射（列名沿用后端 DSL 字段名） */
  var RULE_COL_LABELS = {
    name: '显示名',
    key: '参数名',
    jsonpathMapping: '取值表达式',
    defaultValue: '默认值',
    type: '数据类型',
    inputValue: '输入名',
    idx: '下标引用',
    idxSelectId: '下标绑定',
    Ljsonpath: '左值表达式',
    Rjsonpath: '右值表达式',
    condition: '比较符',
    logic: '逻辑',
    value: '值',
    contentType: '内容类型',
    outputsJsonSchema: '输出 Schema'
  };

  /** 取列名的中文标签 */
  function ruleColLabel(col) {
    return RULE_COL_LABELS[col] || col;
  }

  /* ---- 复用算子（跨实例可见） ---- */
  function allOperators() { return readJSON(K.OPERATORS, {}); }

  /* ---- 当前实例的临时算子 ---- */
  function localOperators(instId) {
    instId = instId || currentInstanceId();
    if (!instId) return {};
    var inst = allInstances()[instId];
    return (inst && inst.localOperators) || {};
  }

  function allLocalOperators() { return localOperators(); }

  /**
   * 列出算子。
   * @param {string} [type]   按 opType 过滤
   * @param {Object} [opts]   { scope: 'shared'|'local'|'all' } 默认 all
   *                          scope=all 时返回当前实例可见的全部算子
   *                          （复用算子 + 当前实例的临时算子）
   */
  function listOperators(type, opts) {
    opts = opts || {};
    var scope = opts.scope || 'all';
    var bucket = {};
    if (scope === 'shared' || scope === 'all') {
      var shared = allOperators();
      Object.keys(shared).forEach(function (k) { bucket[k] = shared[k]; });
    }
    if (scope === 'local' || scope === 'all') {
      var local = allLocalOperators();
      Object.keys(local).forEach(function (k) { bucket[k] = local[k]; });
    }
    return Object.keys(bucket).map(function (k) { return bucket[k]; })
      .filter(function (o) { return !type || o.opType === type; })
      .sort(function (a, b) { return (b.updateTime || 0) - (a.updateTime || 0); });
  }

  /** 取算子（先查复用，再查当前实例的临时算子） */
  function getOperator(id) {
    if (!id) return null;
    var shared = allOperators();
    if (shared[id]) return shared[id];
    return allLocalOperators()[id] || null;
  }

  /**
   * 保存算子。
   * scope=builtin 写入复用表（系统预置）；
   * scope=local 写入当前实例；其余按复用处理。
   */
  function saveOperator(op) {
    var scope = op.scope || OP_SCOPE.SHARED;
    if (!op.id) op.id = uid();
    op.updateTime = Date.now();
    if (!op.createTime) op.createTime = op.updateTime;
    op.scope = scope;

    if (scope === OP_SCOPE.LOCAL) {
      var instId = currentInstanceId();
      if (!instId) return op;                // 无实例上下文则忽略
      var insts = allInstances();
      var inst = insts[instId];
      if (!inst) return op;
      if (!inst.localOperators) inst.localOperators = {};
      inst.localOperators[op.id] = op;
      inst.updateTime = Date.now();
      writeJSON(K.INSTANCES, insts);
      return op;
    }

    var all = allOperators();
    all[op.id] = op;
    writeJSON(K.OPERATORS, all);
    return op;
  }

  /**
   * 注册为复用算子：把临时算子搬到复用表（跨实例可见）。
   * 内置算子不允许注册。
   * @returns {{ok:boolean, msg?:string}}
   */
  function registerOperator(id) {
    var op = getOperator(id);
    if (!op) return { ok: false, msg: '算子不存在' };
    if (op.builtin || op.scope === OP_SCOPE.BUILTIN) {
      return { ok: false, msg: '内置算子无需注册' };
    }
    if (op.scope === OP_SCOPE.SHARED) return { ok: false, msg: '该算子已是复用算子' };

    var instId = currentInstanceId();
    var insts = allInstances();
    var inst = insts[instId];
    /* 从实例的临时算子表移除 */
    if (inst && inst.localOperators && inst.localOperators[id]) {
      delete inst.localOperators[id];
      inst.updateTime = Date.now();
      writeJSON(K.INSTANCES, insts);
    }
    /* 写入复用表 */
    var shared = allOperators();
    op.scope = OP_SCOPE.SHARED;
    op.updateTime = Date.now();
    shared[id] = op;
    writeJSON(K.OPERATORS, shared);
    return { ok: true };
  }

  /**
   * 删除算子。按算子实际所在的位置删除（复用表 / 当前实例的临时表）。
   * 内置算子不允许删除。
   */
  function removeOperators(ids) {
    var shared = allOperators();
    var instId = currentInstanceId();
    var insts = allInstances();
    var inst = insts[instId];
    var sharedChanged = false, localChanged = false;

    (ids || []).forEach(function (id) {
      var op = shared[id] || (inst && inst.localOperators && inst.localOperators[id]);
      if (op && (op.builtin || op.scope === OP_SCOPE.BUILTIN)) return;   // 内置不可删
      if (shared[id]) { delete shared[id]; sharedChanged = true; return; }
      if (inst && inst.localOperators && inst.localOperators[id]) {
        delete inst.localOperators[id];
        localChanged = true;
      }
    });

    if (sharedChanged) writeJSON(K.OPERATORS, shared);
    if (localChanged) {
      inst.updateTime = Date.now();
      writeJSON(K.INSTANCES, insts);
    }
  }

  /** 是否内置算子 */
  function isBuiltinOperator(op) {
    return !!(op && (op.builtin || op.scope === OP_SCOPE.BUILTIN));
  }

  /** 是否复用算子（非内置且 scope=shared） */
  function isSharedOperator(op) {
    return !!(op && !isBuiltinOperator(op) && (op.scope === OP_SCOPE.SHARED || !op.scope));
  }

  /** 是否临时算子（当前实例私有） */
  function isLocalOperator(op) {
    return !!(op && op.scope === OP_SCOPE.LOCAL);
  }

  /**
   * 新建算子的默认模板（按类型）。
   * 默认 scope=local：注册页新建的算子先作为「临时算子」只在本实例可见，
   * 用户勾选「注册为复用算子」后才转为 shared。
   */
  function newOperator(type, scope) {
    type = type || 'http';
    var base = {
      id: '', opType: type, name: '', description: '',
      scope: scope || OP_SCOPE.LOCAL,
      createTime: 0, updateTime: 0
    };
    /* 补偿算子是同族外部算子的副本：data 结构与基础类型完全一致，
       因此这里先把类型归一化到基础类型再填默认字段，
       避免为每种补偿算子维护一份重复的默认值定义。 */
    var t = baseOpType(type);
    if (t === 'sql') {
      base.envId = '';             // 引用环境配置里的数据库连接
      base.database = '';          // 冗余保存库名，便于无配置时只读展示
      base.sql = '';               // 手写 SQL
      base.timeout = 30000;
    } else if (t === 'shell') {
      base.envId = '';             // 引用环境配置里的执行环境
      base.env = '';               // 冗余保存环境名，便于无配置时只读展示
      base.script = '';            // 脚本内容
      base.timeout = 30000;
    } else if (t === 'http') {
      /* 字段严格对齐后端 node_outer_http 的 data 结构 */
      base.protocol = 'http';                  // http | restful
      base.url = '';
      base.requestType = 'GET';                // GET / POST / PUT / DELETE …
      base.outputs = { contentType: 'application/json', outputsJsonSchema: '' };
      base.inputs = {
        reqPath: [], reqQuery: [], reqHeaders: [], reqBodyForm: [],
        reqBodyType: 'json',                   // json | form
        reqBodyOther: '',                      // JSON Schema 串
        bodyJsonSchema: ''                     // 兼容旧字段名
      };
      base.errorno = '';
      base.rollbackRule = { rollbackURL: '', rollbackType: '' };
      base.registeredOperatorId = '';
    } else if (t === 'datamap') {
      base.parentsOutputs = [];
      base.childInputs = {
        reqPath: [], reqQuery: [], reqHeaders: [], reqBodyForm: [],
        reqBodyType: 'json',
        reqBodyOther: '',                      // JSON Schema 串
        reqBodyJson: ''                        // JSON Schema 串
      };
    } else if (t === 'transform2obj') {
      base.transformRules = [];
    } else if (t === 'dataresult') {
      base.resultRules = [];
    } else if (t === 'decision') {
      base.conditionData = [];
    } else if (t === 'execPython') {
      base.script = ''; base.interpreter = 'python3'; base.timeout = 30000;
    }
    /* 注：webservice 目前仅在后端定义（样本见 dslDemo.json 的 node_outer_webservice），
       前端暂未提供字段定义，其补偿版 webserviceCompensate 会自动复用同一份定义，
       无需在此追加 xxxCompensate 分支。 */
    return base;
  }

  /* ============ 内置算子播种 ============
   * 把 resources 中定义的内置算子预置为「已注册算子」，
   * 使用户可在编排页拖拽、在算子注册页编辑。
   * 已存在（含用户删改过的）不覆盖，仅补齐缺失项。
   */
  /* v3：清理 OP_TYPES 中已不存在的孤儿播种记录
     （如 dubboCompensate 及样本中从未出现的 sleep / arrayExtract 等） */
  var SEEDED_FLAG = 'se_arr_op_seeded_v3';

  /** 播种记录的 id 前缀：用于区分「系统预置」与「用户自建」 */
  var SEED_PREFIX = 'builtin_';

  function seedBuiltinOperators() {
    var all = allOperators();

    /* 迁移：把历史数据里被误标为 builtin 的补偿算子放开
       （允许改名 / 删除 / 注册为复用） */
    var migrated = 0;
    Object.keys(all).forEach(function (id) {
      var o = all[id];
      var meta = OP_TYPES[o.opType];
      if (meta && isCompensateGroup(meta.group) && o.builtin) {
        o.builtin = false;
        o.scope = OP_SCOPE.LOCAL;
        o.updateTime = Date.now();
        migrated++;
      }
      /* 同步分组名：早期版本把补偿算子分成 compensateInner / compensateOuter，
         现已合并为 compensate。算子上存的是分组快照，不刷新会与新分组对不上。 */
      if (meta && o.group !== meta.group) {
        o.group = meta.group;
        o.updateTime = Date.now();
        migrated++;
      }
    });
    if (migrated) writeJSON(K.OPERATORS, all);

    /* 清理：删除历史版本播种、但现已从 OP_TYPES 移除的算子。
       判定条件同时满足才删，避免误伤用户自建算子：
         a) id 带播种前缀 builtin_  —— 说明是系统预置而非用户创建
         b) id 恰好等于 builtin_ + opType，且该 opType 已不在 OP_TYPES 中
       注意补偿算子 builtin 为 false，故不能用 builtin 字段作为判据。 */
    var removed = 0;
    Object.keys(all).forEach(function (id) {
      if (id.indexOf(SEED_PREFIX) !== 0) return;
      var o = all[id];
      if (!o || OP_TYPES[o.opType]) return;
      if (id !== SEED_PREFIX + o.opType) return;   // 用户改过 id 的不动
      delete all[id];
      removed++;
    });
    if (removed) writeJSON(K.OPERATORS, all);

    if (readJSON(SEEDED_FLAG, false)) return migrated + removed;

    var added = 0;
    Object.keys(OP_TYPES).forEach(function (t) {
      var meta = OP_TYPES[t];
      if (meta.group === 'edge') return;              // 连线不作为算子
      if (REGISTERABLE_TYPES.indexOf(t) >= 0) return;  // 外部算子由用户自行注册
      var id = SEED_PREFIX + t;
      if (all[id]) return;
      var op = newOperator(t);
      op.id = id;
      op.name = meta.name;
      op.description = meta.desc;
      op.group = meta.group;
      /* 补偿算子（group=compensate）允许改名，因此不标记为 builtin；
         它们只是「预置算子」，仍可编辑、可删除、可注册为复用。 */
      if (isCompensateGroup(meta.group)) {
        op.builtin = false;
        op.scope = OP_SCOPE.LOCAL;
      } else {
        op.builtin = true;
        op.scope = OP_SCOPE.BUILTIN;   // 内置算子：不可改名、不可注册、不可删除
      }
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
    if (!inst.localOperators) inst.localOperators = {};
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
      localOperators: {},     // 本实例私有的临时算子
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

  /* ============ 环境配置 ============
   * shell 算子的「执行环境」与 sql 算子的「数据库」共用一套配置表，
   * 通过 kind 区分：
   *   kind = 'shell'  → { host, port, username, authType, password/privateKey, workDir }
   *   kind = 'sql'    → { dbType, host, port, database, username, password, params }
   * 算子只保存 envId（引用键），配置改动对所有引用它的算子即时生效。
   */
  var ENV_KINDS = {
    shell: {
      name: 'Shell 执行环境',
      icon: '$',
      fields: [
        { key: 'host', label: '主机地址', required: true, placeholder: '192.168.1.10' },
        { key: 'port', label: 'SSH 端口', type: 'number', dft: 22 },
        { key: 'username', label: '登录用户', required: true, placeholder: 'root' },
        { key: 'authType', label: '认证方式', type: 'select', options: ['password', 'privateKey'] },
        { key: 'password', label: '登录密码', type: 'password' },
        { key: 'privateKey', label: '私钥内容', type: 'textarea' },
        { key: 'workDir', label: '工作目录', placeholder: '/opt/app' }
      ]
    },
    sql: {
      name: '数据库连接',
      icon: '⛁',
      fields: [
        { key: 'dbType', label: '数据库类型', type: 'select',
          options: ['mysql', 'postgresql', 'oracle', 'sqlserver', 'dameng'] },
        { key: 'host', label: '主机地址', required: true, placeholder: '127.0.0.1' },
        { key: 'port', label: '端口', type: 'number', dft: 3306 },
        { key: 'database', label: '库名', required: true, placeholder: 'order_db' },
        { key: 'username', label: '用户名', required: true, placeholder: 'root' },
        { key: 'password', label: '密码', type: 'password' },
        { key: 'params', label: '连接参数', placeholder: 'useSSL=false&serverTimezone=UTC' }
      ]
    }
  };

  /* 按类型给出连接串预览，便于用户核对配置 */
  function envSummary(env) {
    if (!env) return '';
    if (env.kind === 'shell') {
      var auth = env.authType === 'privateKey' ? '私钥' : '密码';
      return (env.username || '?') + '@' + (env.host || '?') + ':' + (env.port || 22)
        + '（' + auth + '）' + (env.workDir ? ' · ' + env.workDir : '');
    }
    var t = env.dbType || 'mysql';
    var defaultPort = { mysql: 3306, postgresql: 5432, oracle: 1521, sqlserver: 1433, dameng: 5236 };
    return t + '://' + (env.username || '?') + '@' + (env.host || '?')
      + ':' + (env.port || defaultPort[t] || 3306) + '/' + (env.database || '?');
  }

  function allEnvs() { return readJSON(K.ENVS, {}); }

  function listEnvs(kind) {
    var all = allEnvs();
    return Object.keys(all).map(function (k) { return all[k]; })
      .filter(function (e) { return !kind || e.kind === kind; })
      .sort(function (a, b) { return (a.name || '').localeCompare(b.name || ''); });
  }

  function getEnv(id) { return id ? (allEnvs()[id] || null) : null; }

  function newEnv(kind) {
    kind = ENV_KINDS[kind] ? kind : 'shell';
    var env = { id: '', kind: kind, name: '', description: '', lastTest: null };
    ENV_KINDS[kind].fields.forEach(function (f) {
      env[f.key] = f.dft !== undefined ? f.dft : (f.type === 'select' ? f.options[0] : '');
    });
    return env;
  }

  function saveEnv(env) {
    if (!env || !env.kind) return { ok: false, msg: '环境配置缺少类型' };
    if (!env.name || !String(env.name).trim()) return { ok: false, msg: '请填写环境名称' };
    var def = ENV_KINDS[env.kind];
    /* 必填校验：把字段标签回传给调用方用于提示 */
    for (var i = 0; i < def.fields.length; i++) {
      var f = def.fields[i];
      if (f.required && (env[f.key] === undefined || env[f.key] === null || String(env[f.key]).trim() === '')) {
        return { ok: false, msg: '请填写「' + f.label + '」' };
      }
    }
    var all = allEnvs();
    if (!env.id) env.id = uid();
    env.name = String(env.name).trim();
    env.updateTime = Date.now();
    if (!env.createTime) env.createTime = env.updateTime;
    delete env.__new;            // 界面态标记，不落库
    all[env.id] = env;
    writeJSON(K.ENVS, all);
    return { ok: true, env: env };
  }

  function removeEnv(id) {
    var all = allEnvs();
    var env = all[id];
    if (!env) return { ok: false, msg: '环境不存在' };
    /* 被算子引用时阻止删除，避免算子指向空配置 —— 先列出引用方让用户自行处理 */
    var refs = listOperators().filter(function (op) {
      return op.envId === id;
    });
    if (refs.length) {
      return {
        ok: false,
        msg: '该环境正被 ' + refs.length + ' 个算子引用（'
          + refs.slice(0, 3).map(function (o) { return o.name; }).join('、')
          + (refs.length > 3 ? ' 等' : '') + '），请先调整这些算子的环境'
      };
    }
    delete all[id];
    writeJSON(K.ENVS, all);
    return { ok: true };
  }

  /** 记录一次连通性测试结果（在环境配置上留痕，便于回看） */
  function markEnvTest(id, result) {
    var all = allEnvs();
    var env = all[id];
    if (!env) return null;
    env.lastTest = {
      ok: !!result.ok,
      msg: result.msg || '',
      time: Date.now(),
      latency: result.latency || 0
    };
    writeJSON(K.ENVS, all);
    return env;
  }

  /** 首次使用时空表会很难用，预置几条与旧硬编码选项一致的示例配置 */
  var ENV_SEEDED = 'sa.envs_seeded_v1';
  function seedEnvs() {
    if (readJSON(ENV_SEEDED, false)) return 0;
    var presets = [
      { kind: 'shell', name: 'linux-prod-01', host: '192.168.10.11', port: 22, username: 'deploy', authType: 'password' },
      { kind: 'shell', name: 'linux-test-01', host: '192.168.20.11', port: 22, username: 'deploy', authType: 'password' },
      { kind: 'sql', name: 'mysql-main', dbType: 'mysql', host: '192.168.10.21', port: 3306, database: 'main_db', username: 'app' },
      { kind: 'sql', name: 'pg-order', dbType: 'postgresql', host: '192.168.10.22', port: 5432, database: 'order_db', username: 'app' }
    ];
    var all = allEnvs(), added = 0;
    presets.forEach(function (p) {
      var env = newEnv(p.kind);
      Object.keys(p).forEach(function (k) { env[k] = p[k]; });
      env.id = uid();
      env.createTime = env.updateTime = Date.now();
      env.sample = true;          // 标记为预置示例，便于 UI 提示
      all[env.id] = env;
      added++;
    });
    writeJSON(K.ENVS, all);
    writeJSON(ENV_SEEDED, true);
    return added;
  }

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

        /* 开始节点：把全局参数转成后端 KeyValueDto 结构 */
        if (c.cellType === 'node_start') {
          cell.data = Object.assign({}, cell.data);
          /* requestMethod 已从表单移除（开始节点只声明全局参数），
             这里一并清理，避免历史实例继续把该字段带入 DSL。 */
          delete cell.data.requestMethod;
          cell.data.globals = toKeyValueList(c.data && c.data.globals);
        }
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

    /* 后端 DSL 顶层只有 cells 与 groups；
       全局参数是开始节点 data.globals 的一部分，不额外挂在顶层。 */
    return { cells: cells, groups: groups };
  }

  function fromDSL(dsl) {
    var cells = sanitizeCells((dsl.cells || []).map(function (c) {
      var cell = Object.assign({}, c);
      /* 开始节点：把后端 KeyValueDto 还原为前端参数项（补本地 id） */
      if (cell.cellType === 'node_start') {
        cell.data = Object.assign({}, cell.data);
        cell.data.globals = fromKeyValueList(cell.data.globals);
      }
      return cell;
    }));
    var nodes = cells.filter(function (c) { return !DslValidator.isEdge(c.cellType); });
    var edges = cells.filter(function (c) { return DslValidator.isEdge(c.cellType); });
    /* 只对缺少坐标的节点做自动布局 */
    layoutAuto(nodes, edges);
    return {
      cells: cells,
      groups: (dsl.groups || []).map(function (g) { return Object.assign({ nodes: [] }, g); })
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

  /* ============ 全局参数 ============
   * 全局参数挂在开始节点的 data.globals 上，与后端 StartCell.Data.globals
   * （List<KeyValueDto>）完全对应。
   *
   * 权威样本取自 hrh_dslDemo1.json 的开始节点：
   *   { "name": "类别", "key": "wftype", "type": "int", "value": 1 }
   * 其中 name 是显示名（中文可读），key 是引用键（表达式里用的标识）。
   * 引用写法：#dynamicParams_$<key>
   */

  /** 全局参数可选类型（int 与 string 对齐后端样本用法） */
  var PARAM_TYPES = ['string', 'int', 'number', 'boolean', 'array', 'object'];

  /**
   * 新建一个全局参数。
   * @param {string} name  显示名
   * @param {string} key   引用键
   * @param {string} type  类型
   * @param {*}      value 值
   */
  function newParam(name, key, type, value) {
    return {
      id: uid(),                     // 前端本地标识，供 selectId / idxSelectId 绑定
      name: name || '',              // 显示名，如「类别」
      key: key || '',                // 引用键，如 wftype
      type: type || 'string',
      value: (value === undefined || value === null) ? '' : value
    };
  }

  /** 新建静态参数（流程级常量） */
  function newStaticParam(name, key, type, value) { return newParam(name, key, type, value); }

  /** 新建动态参数（可被循环线推进） */
  function newDynamicParam(name, key, type, value) {
    return newParam(name, key, type || 'int', value === undefined ? 0 : value);
  }

  /** 取开始节点 cell */
  function startCell(dsl) {
    var cells = (dsl && dsl.cells) || dsl || [];
    return cells.filter(function (c) { return c.cellType === 'node_start'; })[0] || null;
  }

  /** 取全部全局参数（开始节点的 data.globals） */
  function globalParams(dsl) {
    var c = startCell(dsl);
    return (c && c.data && c.data.globals) || [];
  }

  /** 生成动态参数的引用表达式（用 key，不是 name） */
  function dynamicRef(key) {
    return '#dynamicParams_$' + key;
  }

  /**
   * 前端参数项 → 后端 KeyValueDto。
   * 只输出后端认识的字段：{ name, key, type, value }。
   */
  function toKeyValueList(list) {
    if (!Array.isArray(list)) return [];
    return list.filter(function (p) {
      return p && String(p.key || p.name || '').trim() !== '';
    }).map(function (p) {
      return {
        name: String(p.name || '').trim(),
        key: String(p.key || p.name || '').trim(),
        type: p.type || 'string',
        value: (p.value === undefined || p.value === null) ? '' : p.value
      };
    });
  }

  /**
   * 后端 KeyValueDto → 前端参数项。
   * 补上本地 id（后端不回传），供 selectId / idxSelectId 绑定使用。
   */
  function fromKeyValueList(list) {
    if (!Array.isArray(list)) return [];
    return list.map(function (p) {
      p = p || {};
      var key = p.key || p.name || '';
      return {
        id: p.id || uid(),
        name: p.name || key,
        key: key,
        type: p.type || 'string',
        value: (p.value === undefined || p.value === null) ? '' : p.value
      };
    });
  }

  /** 空白编排（含默认首尾节点；全局参数挂在开始节点上） */
  function createBlank() {
    var startId = uid(), endId = uid();
    var cells = [
      Object.assign({}, NodeDefs.get('node_start').defaultData(), {
        id: startId, cellType: 'node_start', name: '开始', x: 80, y: 200,
        ports: { items: [{ id: startId + '_out' }, { id: startId + '_in' }] }
      }),
      Object.assign({}, NodeDefs.get('node_end').defaultData(), {
        id: endId, cellType: 'node_end', name: '结束', x: 800, y: 200,
        ports: { items: [{ id: endId + '_out' }, { id: endId + '_in' }] }
      })
    ];
    return { cells: cells, groups: [] };
  }

  /* ============ 导出 ============ */
  var Store = {
    uid: uid,
    now: now,
    num: num,
    OP_TYPES: OP_TYPES,
    REGISTERABLE_TYPES: REGISTERABLE_TYPES,
    opTypesByGroup: opTypesByGroup,
    OP_SCHEMAS: OP_SCHEMAS,
    opSchema: opSchema,
    COMPENSATE_OF: COMPENSATE_OF,
    isCompensateType: isCompensateType,
    isCompensateGroup: isCompensateGroup,
    baseOpType: baseOpType,
    compensateTypeOf: compensateTypeOf,
    ruleColLabel: ruleColLabel,

    // 全局参数（挂在开始节点的 data.globals 上）
    PARAM_TYPES: PARAM_TYPES,
    newParam: newParam,
    newStaticParam: newStaticParam,
    newDynamicParam: newDynamicParam,
    startCell: startCell,
    globalParams: globalParams,
    dynamicRef: dynamicRef,
    toKeyValueList: toKeyValueList,
    fromKeyValueList: fromKeyValueList,

    // 算子
    OP_SCOPE: OP_SCOPE,
    allOperators: allOperators,
    localOperators: localOperators,
    listOperators: listOperators,
    getOperator: getOperator,
    saveOperator: saveOperator,
    registerOperator: registerOperator,
    removeOperators: removeOperators,
    newOperator: newOperator,
    seedBuiltinOperators: seedBuiltinOperators,
    isBuiltinOperator: isBuiltinOperator,
    isSharedOperator: isSharedOperator,
    isLocalOperator: isLocalOperator,

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

    // 环境配置（shell 执行环境 / sql 数据库）
    ENV_KINDS: ENV_KINDS,
    envKindFields: function (kind) { return (ENV_KINDS[kind] || {}).fields || []; },
    envSummary: envSummary,
    listEnvs: listEnvs,
    getEnv: getEnv,
    newEnv: newEnv,
    saveEnv: saveEnv,
    removeEnv: removeEnv,
    markEnvTest: markEnvTest,
    seedEnvs: seedEnvs,

    // DSL
    toDSL: toDSL,
    fromDSL: fromDSL,
    createBlank: createBlank
  };

  global.Store = Store;
})(window);
