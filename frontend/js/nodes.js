/* ===== 节点定义 =====
 * 两类节点来源：
 *   1. 内置节点（NODES）—— 基础节点与连线，与后端 CellType.java 对应
 *   2. 已注册算子（动态）—— 从 Store.listOperators() 读取，
 *      cellType 统一为 node_op，具体算子 id 存在 data.opId 中，
 *      实际执行的算子类型（http/sql/shell/内部算子）由 data.opType 指定。
 *
 * 说明：内部算子（数据映射、规则转换、数组提取等）已统一改为「注册算子」形式，
 *      由 Store.seedBuiltinOperators() 预置，因此不再在调色板中硬编码。
 */
(function (global) {
  'use strict';

  /* 分类 */
  var CATS = {
    basic:      { name: '基础节点',   color: '#2563eb', ico: '⚑', desc: '流程的入口、出口与全局参数' },
    edge:       { name: '连线',       color: '#64748b', ico: '⇢', desc: '节点之间的流转关系' },
    opHttp:     { name: 'HTTP 算子',  color: '#14b8a6', ico: '☁', desc: '已注册的 HTTP 接口算子' },
    opSql:      { name: 'SQL 算子',   color: '#8b5cf6', ico: '⛁', desc: '已注册的数据库 SQL 算子' },
    opShell:    { name: 'Shell 算子', color: '#f59e0b', ico: '$', desc: '已注册的脚本算子' },
    opInner:    { name: '内部算子',   color: '#8b5cf6', ico: '⚙', desc: '引擎内置的数据处理能力' },
    compensate: { name: '补偿算子',   color: '#ef4444', ico: '⟲', desc: '失败后的反向操作' }
  };

  /* 已注册算子对应的分类 key（按 OP_TYPES[t].group 映射） */
  var OP_CAT = {
    http: 'opHttp', sql: 'opSql', shell: 'opShell',
    inner: 'opInner', compensate: 'compensate'
  };

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
    }
    /* 内部算子与补偿算子已改为「注册算子」形式，见 Store.seedBuiltinOperators() */
  ];

  var byType = {};
  NODES.forEach(function (n) { byType[n.cellType] = n; });

  /** 取节点定义 */
  function get(cellType) {
    /* 已注册算子节点：按 data.opId 动态取名称与图标 */
    if (cellType === OP_CELLTYPE) {
      return {
        cellType: OP_CELLTYPE, name: '算子节点', cat: 'opInner', kind: 'node', icon: '⚙',
        desc: '已注册的算子', defaultData: function () { return { opId: '', opType: 'http' }; },
        schema: []
      };
    }
    return byType[cellType] || {
      cellType: cellType, name: cellType, cat: 'opInner', kind: 'node', icon: '?',
      desc: '未知节点类型', defaultData: function () { return {}; }, schema: []
    };
  }

  /**
   * 取分类元信息。
   * 对算子节点额外接受 opType，以便按算子真实类型着色
   * （同为 node_op，http 用青色、sql 用紫色、内部算子用紫色、补偿用红色）。
   */
  function categoryOf(cellType, opType) {
    if (cellType === OP_CELLTYPE) {
      var catKey = OP_CAT[opType] || 'opInner';
      return CATS[catKey] || CATS.opInner;
    }
    return CATS[get(cellType).cat] || CATS.opInner;
  }

  /** 按 opType 取分组名（http / sql / shell / inner / compensate） */
  function groupOfOpType(opType) {
    var meta = (global.Store && Store.OP_TYPES[opType]) || null;
    return (meta && meta.group) || opType;
  }
  function isEdgeDef(cellType) { return DslValidator.isEdge(cellType); }
  function isFixed(cellType) { return !!get(cellType).fixed; }
  function isOperatorNode(cellType) { return cellType === OP_CELLTYPE; }

  /**
   * 按分类分组返回节点定义（仅返回可拖拽的节点，不含连线）。
   *
   * 设计说明：连线不是「拖进画布的元素」，而是节点之间的关系，
   * 必须通过节点右侧桩点拖拽生成。因此调色板中不展示连线分组，
   * 线型说明改由画布左下角的图例承担。
   *
   * 分组顺序：基础节点 → 已注册算子（http / sql / shell / 内部算子 / 补偿算子）。
   * 空分组不展示。
   */
  function byCategory() {
    var out = [];
    var ops = (global.Store ? Store.listOperators() : [])
      /* 内置算子按 OP_TYPES 声明顺序排列，用户算子按更新时间倒序在前 */
      .sort(function (a, b) {
        if (a.builtin !== b.builtin) return a.builtin ? 1 : -1;
        return (b.updateTime || 0) - (a.updateTime || 0);
      });

    /* 1. 基础节点 */
    var basics = NODES.filter(function (n) { return n.cat === 'basic'; });
    if (basics.length) out.push({ key: 'basic', meta: CATS.basic, nodes: basics });

    /* 2. 已注册算子，按「算子分组」归入对应分区
     *    注意：这里遍历的是 group（http / sql / shell / inner / compensate），
     *    而算子自身的 opType 可能是具体的内置类型（datamap、arrayExtract …），
     *    因此必须按 (OP_TYPES[opType].group || op.opType) 匹配，不能直接比对 opType。 */
    var order = ['http', 'sql', 'shell', 'inner', 'compensate'];
    order.forEach(function (grp) {
      var catKey = OP_CAT[grp];
      if (!catKey || !CATS[catKey]) return;
      var list = ops.filter(function (o) { return groupOf(o) === grp; }).map(function (o) {
        var meta = Store.OP_TYPES[o.opType] || {};
        return {
          cellType: OP_CELLTYPE,
          opId: o.id,
          opType: o.opType,
          name: o.name || '(未命名算子)',
          cat: catKey,
          kind: 'node',
          icon: meta.icon || '⚙',
          desc: o.description || meta.desc || '',
          builtin: !!o.builtin,
          defaultData: function () { return { opId: o.id, opType: o.opType }; },
          schema: []
        };
      });
      if (list.length) out.push({ key: catKey, meta: CATS[catKey], nodes: list });
    });

    return out;
  }

  /** 取算子所属分组：优先用算子自身 group 字段，其次查 OP_TYPES，最后回落到 opType */
  function groupOf(op) {
    if (op.group) return op.group;
    var meta = (global.Store && Store.OP_TYPES[op.opType]) || null;
    return meta && meta.group ? meta.group : op.opType;
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
