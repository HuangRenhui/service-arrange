/* ===== 节点定义 =====
 * 两类节点来源：
 *   1. 内置节点（NODES）—— 基础节点、连线、内部算子，与后端 CellType.java 对应
 *   2. 已注册算子（动态）—— 从 Store.listOperators() 读取，按 http / sql / shell 分组，
 *      cellType 统一为 node_op，具体算子 id 存在 data.opId 中
 */
(function (global) {
  'use strict';

  /* 分类 */
  var CATS = {
    basic:      { name: '基础节点',   color: '#2563eb', ico: '⚑', desc: '流程的入口、出口与全局参数' },
    edge:       { name: '连线',       color: '#64748b', ico: '⇢', desc: '节点之间的流转关系' },
    inner:      { name: '内部算子',   color: '#8b5cf6', ico: '⚙', desc: '引擎内置的数据处理能力' },
    opHttp:     { name: 'HTTP 算子',  color: '#14b8a6', ico: '☁', desc: '已注册的 HTTP 接口算子' },
    opSql:      { name: 'SQL 算子',   color: '#8b5cf6', ico: '⛁', desc: '已注册的数据库 SQL 算子' },
    opShell:    { name: 'Shell 算子', color: '#f59e0b', ico: '$', desc: '已注册的脚本算子' },
    compensate: { name: '补偿算子',   color: '#ef4444', ico: '⟲', desc: '失败后的反向操作' }
  };

  /* 已注册算子对应的分类 key */
  var OP_CAT = { http: 'opHttp', sql: 'opSql', shell: 'opShell' };

  /* 算子节点统一的 cellType（后端可按此路由，具体算子由 data.opId 指定） */
  var OP_CELLTYPE = 'node_op';

  /* ---------- 内置节点 ---------- */
  var NODES = [
    /* 基础节点 */
    {
      cellType: 'node_start', name: '开始', cat: 'basic', kind: 'node', icon: '▶', fixed: true,
      desc: '流程唯一入口，声明流程入参',
      defaultData: function () {
        return {
          inputsJsonSchema: JSON.stringify({ type: 'object', properties: {} }),
          globals: []
        };
      },
      schema: [
        { key: 'inputsJsonSchema', label: '入参 JSON Schema', type: 'textarea',
          hint: '声明流程启动入参，决定运行时的入参表单' },
        { key: 'globals', label: '流程级常量', type: 'kv', kLabel: '名称', vLabel: '值' }
      ]
    },
    {
      cellType: 'node_end', name: '结束', cat: 'basic', kind: 'node', icon: '■', fixed: true,
      desc: '流程唯一出口，声明流程出参',
      defaultData: function () {
        return {
          contentType: 'application/json',
          outputsJsonSchema: JSON.stringify({ type: 'object', properties: {} }),
          valueJsonpathMapping: ''
        };
      },
      schema: [
        { key: 'contentType', label: '内容类型', type: 'select',
          options: ['application/json', 'application/xml', 'text/plain'] },
        { key: 'outputsJsonSchema', label: '出参 JSON Schema', type: 'textarea' },
        { key: 'valueJsonpathMapping', label: '取值表达式', type: 'text',
          hint: '如 #节点id$jsonpath' }
      ]
    },
    {
      cellType: 'global_Data', name: '全局参数', cat: 'basic', kind: 'node', icon: '≡', fixed: true,
      desc: '声明静态常量与动态变量',
      defaultData: function () { return { staticParams: [], dynamicParams: [] }; },
      schema: [
        { key: 'staticParams', label: '静态参数', type: 'kv', kLabel: '名称', vLabel: '默认值' },
        { key: 'dynamicParams', label: '动态参数', type: 'kv', kLabel: '名称', vLabel: '默认值',
          hint: '可被循环线更新，如计数器 idx' }
      ]
    },

    /* 连线 */
    {
      cellType: 'edge_common', name: '普通线', cat: 'edge', kind: 'edge', icon: '→',
      desc: '顺序流转', defaultData: function () { return {}; }, schema: []
    },
    {
      cellType: 'edge_decision', name: '决策线', cat: 'edge', kind: 'edge', icon: '◇',
      desc: '条件成立才流转',
      defaultData: function () {
        return {
          conditionData: [{ condition: '!=null', logic: 'none', type: '', Rjsonpath: '' }],
          L: [null], R: [''], jsonpathElexpression: 'R0!=null'
        };
      },
      schema: [
        { key: 'jsonpathElexpression', label: '判定表达式', type: 'text',
          hint: '如 R0>100 || R1<90；R0 对应 R 操作数第 1 项' },
        { key: 'R', label: 'R 操作数', type: 'strlist', hint: '取值表达式，如 #节点id$field' },
        { key: 'L', label: 'L 操作数', type: 'strlist' },
        { key: 'conditionData', label: '条件明细', type: 'json' }
      ]
    },
    {
      cellType: 'edge_loop', name: '循环线', cat: 'edge', kind: 'edge', icon: '↻',
      desc: '圈定循环区间并定义循环条件',
      defaultData: function () {
        return {
          loopType: 'doWhile', transformRules: [], R: [], L: [],
          jsonpathElexpression: '',
          cycleInfo: { jsonpathSelect: '#dynamicParams_$idx', rule: '+', type: 'doWhile', stepLength: '1' }
        };
      },
      schema: [
        { key: 'loopType', label: '循环类型', type: 'select', options: ['doWhile', 'whileDo'],
          hint: 'doWhile 先执行后判断；whileDo 先判断后执行' },
        { key: 'jsonpathElexpression', label: '循环条件', type: 'text', hint: '为真时继续循环' },
        { key: 'R', label: 'R 操作数', type: 'strlist' },
        { key: 'L', label: 'L 操作数', type: 'strlist' },
        { key: 'cycleInfo', label: '计数配置', type: 'json' },
        { key: 'transformRules', label: '迭代转换规则', type: 'json' }
      ]
    },
    {
      cellType: 'edge_compensate', name: '补偿线', cat: 'edge', kind: 'edge', icon: '⟲',
      desc: '源节点失败时转入补偿分支', defaultData: function () { return {}; }, schema: []
    },

    /* 内部算子 */
    {
      cellType: 'node_inner_datamap', name: '数据映射', cat: 'inner', kind: 'node', icon: '⇄',
      desc: '把上游输出映射为下游入参',
      defaultData: function () {
        return {
          parentsOutputs: [],
          childInputs: { reqPath: [], reqQuery: [], reqHeaders: [], reqBodyForm: [], reqBodyOther: '' }
        };
      },
      schema: [
        { key: 'childInputs.reqPath', label: 'Path 参数', type: 'kvpath' },
        { key: 'childInputs.reqQuery', label: 'Query 参数', type: 'kvpath' },
        { key: 'childInputs.reqHeaders', label: 'Header', type: 'kvpath' },
        { key: 'childInputs.reqBodyForm', label: '表单参数', type: 'kvpath' },
        { key: 'childInputs.reqBodyOther', label: '请求体 Schema', type: 'textarea' }
      ]
    },
    {
      cellType: 'node_inner_sleep', name: '延时', cat: 'inner', kind: 'node', icon: '⏱',
      desc: '暂停指定时长',
      defaultData: function () { return { milliseconds: 1000 }; },
      schema: [{ key: 'milliseconds', label: '毫秒', type: 'number' }]
    },
    {
      cellType: 'node_inner_transform2Obj', name: '规则转换', cat: 'inner', kind: 'node', icon: '⇉',
      desc: '按规则重组 JSON',
      defaultData: function () { return { transformRuleList: [] }; },
      schema: [{ key: 'transformRuleList', label: '转换规则', type: 'rules',
        hint: 'key 目标字段 / keyType 类型 / type 规则 / jsonpathMapping 表达式 / defaultValue 兜底' }]
    },
    {
      cellType: 'node_inner_dataresult', name: '数据结果', cat: 'inner', kind: 'node', icon: '▦',
      desc: '整理并输出结果',
      defaultData: function () { return { resultRules: [] }; },
      schema: [{ key: 'resultRules', label: '结果规则', type: 'kv' }]
    },
    {
      cellType: 'node_inner_compensateDatamap', name: '补偿数据映射', cat: 'inner', kind: 'node', icon: '⇄',
      desc: '为补偿算子组装入参',
      defaultData: function () {
        return { parentsOutputs: [], childInputs: { reqPath: [], reqQuery: [], reqHeaders: [], reqBodyForm: [], reqBodyOther: '' } };
      },
      schema: [
        { key: 'childInputs.reqQuery', label: 'Query 参数', type: 'kvpath' },
        { key: 'childInputs.reqHeaders', label: 'Header', type: 'kvpath' },
        { key: 'childInputs.reqBodyOther', label: '请求体 Schema', type: 'textarea' }
      ]
    },

    /* 补偿算子（内置占位，供补偿线挂接） */
    {
      cellType: 'node_outer_httpCompensate', name: 'HTTP 补偿', cat: 'compensate', kind: 'node', icon: '⤺',
      desc: 'HTTP 调用的补偿动作',
      defaultData: function () { return { url: '', method: 'POST', headers: [], body: '' }; },
      schema: [
        { key: 'url', label: '请求地址', type: 'text' },
        { key: 'method', label: '请求方法', type: 'select', options: ['GET', 'POST', 'PUT', 'DELETE'] },
        { key: 'headers', label: '请求头', type: 'kv' },
        { key: 'body', label: '请求体', type: 'textarea' }
      ]
    }
  ];

  var byType = {};
  NODES.forEach(function (n) { byType[n.cellType] = n; });

  /** 取节点定义 */
  function get(cellType) {
    /* 已注册算子节点：按 data.opId 动态取名称与图标 */
    if (cellType === OP_CELLTYPE) {
      return {
        cellType: OP_CELLTYPE, name: '算子节点', cat: 'opHttp', kind: 'node', icon: '⚙',
        desc: '已注册的算子', defaultData: function () { return { opId: '', opType: 'http' }; },
        schema: []
      };
    }
    return byType[cellType] || {
      cellType: cellType, name: cellType, cat: 'inner', kind: 'node', icon: '?',
      desc: '未知节点类型', defaultData: function () { return {}; }, schema: []
    };
  }

  function categoryOf(cellType) { return CATS[get(cellType).cat] || CATS.inner; }
  function isEdgeDef(cellType) { return DslValidator.isEdge(cellType); }
  function isFixed(cellType) { return !!get(cellType).fixed; }
  function isOperatorNode(cellType) { return cellType === OP_CELLTYPE; }

  /**
   * 按分类分组返回节点定义。
   * 已注册算子会动态注入到 opHttp / opSql / opShell 三个分组。
   */
  function byCategory() {
    var out = [];
    var ops = (global.Store ? Store.listOperators() : []);

    /* 1. 基础节点 */
    out.push({ key: 'basic', meta: CATS.basic, nodes: NODES.filter(function (n) { return n.cat === 'basic'; }) });
    /* 2. 连线 */
    out.push({ key: 'edge', meta: CATS.edge, nodes: NODES.filter(function (n) { return n.cat === 'edge'; }) });
    /* 3. 内部算子 */
    out.push({ key: 'inner', meta: CATS.inner, nodes: NODES.filter(function (n) { return n.cat === 'inner'; }) });

    /* 4~6. 已注册算子（按 http / sql / shell 分组） */
    ['http', 'sql', 'shell'].forEach(function (t) {
      var catKey = OP_CAT[t];
      var list = ops.filter(function (o) { return o.opType === t; }).map(function (o) {
        return {
          cellType: OP_CELLTYPE,
          opId: o.id,
          opType: o.opType,
          name: o.name || '(未命名算子)',
          cat: catKey,
          kind: 'node',
          icon: Store.OP_TYPES[t].icon,
          desc: o.description || Store.OP_TYPES[t].desc,
          defaultData: function () { return { opId: o.id, opType: o.opType }; },
          schema: []
        };
      });
      if (list.length) out.push({ key: catKey, meta: CATS[catKey], nodes: list });
    });

    /* 7. 补偿算子 */
    out.push({ key: 'compensate', meta: CATS.compensate, nodes: NODES.filter(function (n) { return n.cat === 'compensate'; }) });

    return out;
  }

  global.NodeDefs = {
    CATS: CATS,
    NODES: NODES,
    OP_CAT: OP_CAT,
    OP_CELLTYPE: OP_CELLTYPE,
    get: get,
    categoryOf: categoryOf,
    isEdgeDef: isEdgeDef,
    isFixed: isFixed,
    isOperatorNode: isOperatorNode,
    byCategory: byCategory
  };
})(window);
