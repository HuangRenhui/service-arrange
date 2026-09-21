/* ===== 节点定义 =====
 * 两类节点来源：
 *   1. 内置节点（NODES）—— 基础节点与连线，与后端 CellType.java 对应
 *   2. 已注册算子（动态）—— 从 Store.listOperators() 读取，
 *      cellType 统一为 node_op，具体算子 id 存在 data.opId 中，
 *      实际执行的算子类型（http/sql/shell/内部算子）由 data.opType 指定。
 *
 * 说明：内部算子（数据映射、规则转换、数据结果、决策、Python 执行）
 *      与补偿算子已统一改为「注册算子」形式，由 Store.seedBuiltinOperators() 预置，
 *      因此不在调色板中硬编码；补偿算子统一归入「补偿算子」一个分组。
 */
(function (global) {
  'use strict';

  /* 分类
     补偿算子（补偿数据映射 / HTTP 补偿 / WebService 补偿）归入同一个分组，
     它们在同一条补偿链路上各自扮演不同环节，对使用者统称「补偿算子」。 */
  var CATS = {
    basic:      { name: '基础节点',   color: '#2563eb', ico: '⚑', desc: '流程的入口、出口与全局参数' },
    edge:       { name: '连线',       color: '#64748b', ico: '⇢', desc: '节点之间的流转关系' },
    opHttp:     { name: 'HTTP 算子',  color: '#14b8a6', ico: '☁', desc: '已注册的 HTTP 接口算子' },
    opSql:      { name: 'SQL 算子',   color: '#8b5cf6', ico: '⛁', desc: '已注册的数据库 SQL 算子' },
    opShell:    { name: 'Shell 算子', color: '#f59e0b', ico: '$', desc: '已注册的脚本算子' },
    opInner:    { name: '内部算子',   color: '#8b5cf6', ico: '⚙', desc: '引擎内置的数据处理能力' },
    compensate: { name: '补偿算子',   color: '#ef4444', ico: '⟲', desc: '失败后的反向操作与入参组装' }
  };

  /* 已注册算子对应的分类 key（按分组名映射） */
  var OP_CAT = {
    http: 'opHttp', sql: 'opSql', shell: 'opShell',
    inner: 'opInner', compensate: 'compensate'
  };

  /* 外部算子类型：以自身 opType 作为分组名（http / sql / shell） */
  var OUTER_OP_TYPES = ['http', 'sql', 'shell'];

  /* 算子节点统一的 cellType（后端可按此路由，具体算子由 data.opId 指定） */
  var OP_CELLTYPE = 'node_op';

  /* ---------- 内置节点 ---------- */
  var NODES = [
    /* 基础节点 */
    {
      /* 开始节点：流程唯一入口，同时承载全局参数声明。
         全局参数放在开始节点的 data.globals 上（对应后端 StartCell.Data.globals，
         结构为 List<KeyValueDto>），不再使用独立的 global_Data 节点。
         入参 JSON Schema 仍保留（后端 DslParser 依赖它做入参类型还原），
         但作为「高级设置」默认折叠。
         palette:false —— 由 createBlank() 自动生成，不允许从调色板重复拖出。 */
      cellType: 'node_start', name: '开始', cat: 'basic', kind: 'node', icon: '▶',
      fixed: true, palette: false,
      desc: '流程唯一入口，声明流程全局参数',
      defaultData: function () {
        return {
          inputsJsonSchema: JSON.stringify({ type: 'object', properties: {} }),
          globals: []
        };
      },
      schema: [
        { key: 'globals', label: '全局参数', type: 'params',
          hint: '引用写法 #dynamicParams_$引用键，如 #dynamicParams_$wftype' },
        /* 入参 Schema 决定运行实例的入参表单与类型还原（后端 DslParser
           依赖它把表单值还原为数字/布尔等），因此保留能力但默认折叠，
           避免与「全局参数」这一主要配置混淆。 */
        { key: 'inputsJsonSchema', label: '入参 JSON Schema', type: 'textarea',
          advanced: true, hint: '声明流程启动入参，决定运行时的入参表单与类型还原' }
      ]
    },
    {
      /* 结束节点：同样由 createBlank() 自动生成，不从调色板拖出 */
      cellType: 'node_end', name: '结束', cat: 'basic', kind: 'node', icon: '■',
      fixed: true, palette: false,
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

    /* 连线 */
    {
      cellType: 'edge_common', name: '普通线', cat: 'edge', kind: 'edge', icon: '→',
      desc: '顺序流转', defaultData: function () { return {}; }, schema: []
    },
    {
      cellType: 'edge_decision', name: '决策线', cat: 'edge', kind: 'edge', icon: '◇',
      desc: '条件成立才流转',
      /* 结构对齐后端 edge_decision：
         jsonpathElexpression（判定表达式）/ L[]、R[]（操作数）/ conditionData[]（条件明细） */
      defaultData: function () {
        return {
          conditionData: [{ condition: '!=', logic: 'none', type: 'string', Rjsonpath: '' }],
          L: [''], R: [''], jsonpathElexpression: 'R0!=L0'
        };
      },
      schema: [
        { key: 'jsonpathElexpression', label: '判定表达式', type: 'text',
          hint: '如 R0!=L0；R0 对应 R 操作数第 1 项，L0 对应 L 操作数第 1 项' },
        { key: 'R', label: 'R 操作数', type: 'strlist', hint: '取值表达式，如 #节点id$field' },
        { key: 'L', label: 'L 操作数', type: 'strlist' },
        { key: 'conditionData', label: '条件明细', type: 'rules',
          cols: ['Ljsonpath', 'condition', 'logic', 'Rjsonpath', 'type'],
          colTypes: {
            condition: ['==', '!=', '>', '>=', '<', '<=', 'contains', 'notNull'],
            logic: ['none', 'and', 'or'],
            type: ['string', 'number', 'boolean']
          },
          hint: '逐行配置判断条件；Ljsonpath / Rjsonpath 为取值表达式' }
      ]
    },
    {
      cellType: 'edge_loop', name: '循环线', cat: 'edge', kind: 'edge', icon: '↻',
      desc: '圈定循环区间并定义循环条件',
      /* 结构对齐后端 edge_loop：
         cycleInfo（计数配置）/ L[]、R[] / conditionData[] / jsonpathElexpression */
      defaultData: function () {
        return {
          transformRules: [], R: [], L: [],
          jsonpathElexpression: '',
          cycleInfo: {
            jsonpathSelect: '#dynamicParams_$idx', rule: '+',
            type: 'doWhile', stepLength: 1
          }
        };
      },
      schema: [
        { key: 'jsonpathElexpression', label: '循环条件', type: 'text',
          hint: '为真时继续循环，如 R0<=L0' },
        { key: 'R', label: 'R 操作数', type: 'strlist' },
        { key: 'L', label: 'L 操作数', type: 'strlist' },
        { key: 'cycleInfo.type', label: '循环类型', type: 'select',
          options: ['doWhile', 'whileDo'],
          hint: 'doWhile 先执行后判断；whileDo 先判断后执行' },
        { key: 'cycleInfo.jsonpathSelect', label: '计数变量引用', type: 'text',
          hint: '如 #dynamicParams_$idx，指向全局参数中的计数器' },
        { key: 'cycleInfo.rule', label: '递进方式', type: 'select', options: ['+', '-'] },
        { key: 'cycleInfo.stepLength', label: '步长', type: 'number' },
        { key: 'conditionData', label: '条件明细', type: 'rules',
          cols: ['Ljsonpath', 'condition', 'logic', 'Rjsonpath', 'type'],
          colTypes: {
            condition: ['==', '!=', '>', '>=', '<', '<=', 'contains', 'notNull'],
            logic: ['none', 'and', 'or'],
            type: ['string', 'number', 'boolean']
          } }
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
      /* OP_CAT 的键是分组名（http / sql / shell / inner / compensate），
         而 opType 可能是具体类型（如 httpCompensate），因此统一先归一为分组名再查，
         否则补偿算子会因查不到 key 而回落到默认的紫色（内部算子色）。 */
      var grp = groupOfOpType(opType);
      var catKey = OP_CAT[opType] || OP_CAT[grp] || 'opInner';
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
   * 分组顺序：基础节点 → 已注册算子（http / sql / shell / 内部算子 /
   * 内部补偿算子 / 外部补偿算子）。空分组不展示。
   */
  function byCategory() {
    var out = [];
    var ops = (global.Store ? Store.listOperators() : [])
      /* 内置算子按 OP_TYPES 声明顺序排列，用户算子按更新时间倒序在前 */
      .sort(function (a, b) {
        if (a.builtin !== b.builtin) return a.builtin ? 1 : -1;
        return (b.updateTime || 0) - (a.updateTime || 0);
      });

    /* 1. 基础节点（开始 / 结束标记了 palette:false，不在此列出） */
    var basics = NODES.filter(function (n) {
      return n.cat === 'basic' && n.palette !== false;
    });
    if (basics.length) out.push({ key: 'basic', meta: CATS.basic, nodes: basics });

    /* 2. 已注册算子，按「算子分组」归入对应分区
     *    注意：这里遍历的是 group（http / sql / shell / inner / compensate），
     *    而算子自身的 opType 可能是具体的内置类型（datamap、arrayExtract …），
     *    因此必须按 (OP_TYPES[opType].group || op.opType) 匹配，不能直接比对 opType。
     *
     *    外部算子（http / sql / shell）额外常驻一个「模板」项：
     *    拖入画布即自动创建对应的临时算子，无需先去注册页。 */
    var order = ['http', 'sql', 'shell', 'inner', 'compensate'];
    order.forEach(function (grp) {
      var catKey = OP_CAT[grp];
      if (!catKey || !CATS[catKey]) return;

      var list = [];
      /* 2.1 外部算子前置一个模板项 */
      if (OUTER_OP_TYPES.indexOf(grp) >= 0) list.push(makeOuterTemplate(grp, catKey));

      /* 2.2 已有的算子 */
      ops.filter(function (o) { return groupOf(o) === grp; }).forEach(function (o) {
        var meta = Store.OP_TYPES[o.opType] || {};
        /* 作用域：builtin 内置 / shared 复用 / local 临时（仅当前实例） */
        var scope = Store.isBuiltinOperator(o) ? 'builtin'
          : (Store.isLocalOperator(o) ? 'local' : 'shared');
        list.push({
          cellType: OP_CELLTYPE,
          opId: o.id,
          opType: o.opType,
          name: o.name || '(未命名算子)',
          cat: catKey,
          kind: 'node',
          icon: meta.icon || '⚙',
          desc: o.description || meta.desc || '',
          builtin: !!o.builtin,
          scope: scope,
          defaultData: function () { return { opId: o.id, opType: o.opType }; },
          schema: []
        });
      });

      if (list.length) out.push({ key: catKey, meta: CATS[catKey], nodes: list });
    });

    return out;
  }

  /**
   * 构造外部算子的「模板」节点定义。
   * 载荷为 tpl:<opType>，拖入画布时由 Designer 创建一个临时算子并绑定。
   */
  function makeOuterTemplate(opType, catKey) {
    var meta = (global.Store && Store.OP_TYPES[opType]) || { icon: '⚙', desc: '' };
    return {
      cellType: OP_CELLTYPE,
      opId: null,                 // 尚未绑定具体算子
      opType: opType,
      isTemplate: true,           // 标记：拖入时需即时创建算子
      name: '新建' + meta.name,
      cat: catKey,
      kind: 'node',
      icon: meta.icon || '⚙',
      desc: '拖入后新建一个' + meta.name + '，在右侧面板填写配置',
      scope: 'template',
      defaultData: function () { return { opId: '', opType: opType }; },
      schema: []
    };
  }

  /**
   * 取算子所属的调色板分组。
   *
   * 外部算子（http / sql / shell）直接以自身 opType 作为分组名；
   * 内部算子与补偿算子则查 OP_TYPES[t].group（inner / compensate）。
   *
   * 注意：
   * 1) 不能一律用 OP_TYPES[t].group —— 外部算子的 group 是 'outer'，
   *    它只是「外部」这个大类，真正的分组应细到 http / sql / shell。
   * 2) 必须优先查 OP_TYPES 而不是 op.group。op.group 是播种时写入算子的快照，
   *    早期版本写入的值（如补偿算子的 compensateInner / compensateOuter）
   *    会残留在 localStorage 里；若优先取它，已变更的分组名永远不会生效，
   *    甚至因 CATS 中查不到对应项而导致算子从调色板消失。
   *    op.group 仅作为 OP_TYPES 缺失时的兜底。
   */
  function groupOf(op) {
    if (!op) return '';
    if (OUTER_OP_TYPES.indexOf(op.opType) >= 0) return op.opType;
    var meta = (global.Store && Store.OP_TYPES[op.opType]) || null;
    if (meta && meta.group && meta.group !== 'outer') return meta.group;
    if (op.group && op.group !== 'outer') return op.group;
    return op.opType;
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
