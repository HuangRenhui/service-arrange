/* ===== DSL 校验 =====
 * 规则 R1~R9 见 docs/02-DSL规范.md §7
 */
(function (global) {
  'use strict';

  var EDGE_TYPES = ['edge_common', 'edge_loop', 'edge_decision', 'edge_compensate'];

  /**
   * 判断是否为连线类型。
   * 同时接受两种入参形式，避免调用方混淆：
   *   isEdge('edge_common')            —— 直接传 cellType 字符串
   *   isEdge({ cellType: 'edge_...' })  —— 传 Cell 对象
   */
  function isEdge(c) {
    if (!c) return false;
    var t = (typeof c === 'string') ? c : c.cellType;
    return EDGE_TYPES.indexOf(t) >= 0;
  }

  /**
   * @param {Object} dsl  { cells: [...], groups: [...] }
   *                     全局参数是开始节点 data.globals 的一部分
   * @returns {{ok:boolean, errors:string[], warnings:string[]}}
   */
  function validate(dsl) {
    var errors = [];
    var warnings = [];

    if (!dsl || !Array.isArray(dsl.cells)) {
      return { ok: false, errors: ['DSL 结构非法：缺少 cells 数组'], warnings: [] };
    }
    var cells = dsl.cells;
    var nodes = cells.filter(function (c) { return !isEdge(c); });
    var edges = cells.filter(isEdge);
    var nodeIds = {};
    nodes.forEach(function (n) { nodeIds[n.id] = n; });

    /* ---- 基础唯一性 ---- */
    var idSeen = {};
    cells.forEach(function (c) {
      if (!c.id) errors.push('存在缺少 id 的元素（cellType=' + c.cellType + '）');
      else if (idSeen[c.id]) errors.push('元素 id 重复：' + c.id);
      if (c.id) idSeen[c.id] = true;
    });

    /* ---- R1 唯一首尾节点 ---- */
    var starts = nodes.filter(function (n) { return n.cellType === 'node_start'; });
    var ends = nodes.filter(function (n) { return n.cellType === 'node_end'; });
    if (starts.length === 0) errors.push('R1：缺少开始节点 node_start');
    if (starts.length > 1) errors.push('R1：开始节点不唯一（' + starts.length + ' 个）');
    if (ends.length === 0) errors.push('R1：缺少结束节点 node_end');
    if (ends.length > 1) errors.push('R1：结束节点不唯一（' + ends.length + ' 个）');
    if (starts.length !== 1 || ends.length !== 1) {
      return { ok: false, errors: errors, warnings: warnings };
    }
    var startId = starts[0].id;
    var endId = ends[0].id;

    /* ---- R2 连线端点存在 ---- */
    var inDeg = {}, outDeg = {};
    nodes.forEach(function (n) { inDeg[n.id] = 0; outDeg[n.id] = 0; });
    edges.forEach(function (e) {
      var s = e.source && e.source.cell;
      var t = e.target && e.target.cell;
      if (!s || !t) {
        errors.push('R2：连线 ' + e.id + ' 缺少 source 或 target');
        return;
      }
      if (!nodeIds[s]) errors.push('R2：连线 ' + e.id + ' 的起点节点不存在：' + s);
      if (!nodeIds[t]) errors.push('R2：连线 ' + e.id + ' 的终点节点不存在：' + t);
      if (nodeIds[s]) outDeg[s]++;
      if (nodeIds[t]) inDeg[t]++;
    });

    /* ---- R3 桩点引用 ---- */
    edges.forEach(function (e) {
      var s = e.source && e.source.cell;
      var t = e.target && e.target.cell;
      [ [s, e.source], [t, e.target] ].forEach(function (pair) {
        var nid = pair[0], ep = pair[1];
        if (!nid || !ep || !ep.port || !nodeIds[nid]) return;
        var ports = (nodeIds[nid].ports && nodeIds[nid].ports.items) || [];
        var found = ports.some(function (p) { return p.id === ep.port; });
        if (!found) warnings.push('R3：连线 ' + e.id + ' 引用了节点 ' + nid + ' 上不存在的桩点 ' + ep.port);
      });
    });

    /* ---- R4 / R5 入出度 ---- */
    nodes.forEach(function (n) {
      if (n.cellType !== 'node_start' && inDeg[n.id] === 0) {
        errors.push('R4：节点「' + (n.name || n.cellType) + '」没有入线');
      }
      if (n.cellType !== 'node_end' && outDeg[n.id] === 0) {
        errors.push('R4：节点「' + (n.name || n.cellType) + '」没有出线');
      }
    });

    /* ---- R6 首尾入出度 ---- */
    if (inDeg[startId] > 0) errors.push('R6：开始节点不允许有入线');
    if (outDeg[endId] > 0) errors.push('R6：结束节点不允许有出线');

    /* ---- R7 决策线/循环线表达式 ---- */
    edges.forEach(function (e) {
      if (e.cellType === 'edge_decision') {
        if (!e.data || !e.data.jsonpathElexpression) {
          errors.push('R7：决策线 ' + e.id + ' 缺少判定表达式');
        }
      }
      if (e.cellType === 'edge_loop') {
        if (!e.data || !e.data.jsonpathElexpression) {
          warnings.push('R7：循环线 ' + e.id + ' 未设置循环条件（将无限循环）');
        }
        if (!e.data || !e.data.cycleInfo || !e.data.cycleInfo.jsonpathSelect) {
          warnings.push('R7：循环线 ' + e.id + ' 未设置计数变量');
        }
      }
    });

    /* ---- R8 分组引用 ---- */
    var groupIds = {};
    (dsl.groups || []).forEach(function (g) { groupIds[g.id] = g; });
    nodes.forEach(function (n) {
      (n.groupIds || []).forEach(function (gid) {
        if (!groupIds[gid]) errors.push('R8：节点「' + (n.name || n.cellType) + '」引用了不存在的强组合 ' + gid);
      });
    });

    /* ---- R9 Schema 合法性 ---- */
    nodes.forEach(function (n) {
      var d = n.data || {};
      if (n.cellType === 'node_start' && d.inputsJsonSchema) {
        var r1 = SchemaUtil.validateSchemaString(d.inputsJsonSchema);
        if (!r1.ok) errors.push('R9：开始节点入参 Schema 非法（' + r1.msg + '）');
      }
      if (n.cellType === 'node_end' && d.outputsJsonSchema) {
        var r2 = SchemaUtil.validateSchemaString(d.outputsJsonSchema);
        if (!r2.ok) errors.push('R9：结束节点出参 Schema 非法（' + r2.msg + '）');
      }
    });

    /* ---- R10 算子引用有效 ----
     * 节点引用的算子必须仍然存在，否则运行期无法解析出实际调用。 */
    nodes.forEach(function (n) {
      if (n.cellType !== 'node_op') return;
      var opId = n.data && n.data.opId;
      if (!opId) {
        errors.push('R10：节点「' + (n.name || n.cellType) + '」未绑定算子');
        return;
      }
      if (global.Store && !Store.getOperator(opId)) {
        errors.push('R10：节点「' + (n.name || n.cellType) + '」引用的算子已不存在（' + opId + '），请重新绑定');
      }
    });

    /* ---- 可达性 ---- */
    var adjacency = {};
    nodes.forEach(function (n) { adjacency[n.id] = []; });
    edges.forEach(function (e) {
      var s = e.source && e.source.cell, t = e.target && e.target.cell;
      if (adjacency[s] && nodeIds[t]) adjacency[s].push(t);
    });
    var visited = {};
    (function dfs(id) {
      if (visited[id]) return;
      visited[id] = true;
      (adjacency[id] || []).forEach(dfs);
    })(startId);
    nodes.forEach(function (n) {
      if (!visited[n.id]) warnings.push('不可达：节点「' + (n.name || n.cellType) + '」从开始节点无法到达');
    });

    /* ---- 补偿组提示 ---- */
    nodes.forEach(function (n) {
      if ((n.groupIds || []).length) {
        var hasComp = edges.some(function (e) {
          return e.cellType === 'edge_compensate' && e.source && e.source.cell === n.id;
        });
        if (!hasComp) {
          warnings.push('节点「' + (n.name || n.cellType) + '」属于补偿组，但没有挂接补偿线');
        }
      }
    });

    /* ---- R11 全局参数引用有效 ----
     * 其他节点通过 #dynamicParams_$<key> 引用全局参数。
     * 引用键必须在开始节点的 data.globals 中存在，否则运行期解析不到值。 */
    var startNode = nodes.filter(function (n) { return n.cellType === 'node_start'; })[0];
    var dynNames = {};
    if (startNode && startNode.data) {
      (startNode.data.globals || []).forEach(function (p) {
        if (p && p.key) dynNames[p.key] = p;
      });
    }
    var dynCount = Object.keys(dynNames).length;
    var anyRef = false;
    nodes.concat(edges).forEach(function (c) {
      var raw = JSON.stringify(c.data || {});
      if (raw.indexOf('#dynamicParams_$') >= 0) anyRef = true;
      var re = /#dynamicParams_\$([A-Za-z_][A-Za-z0-9_]*)/g;
      var m;
      while ((m = re.exec(raw)) !== null) {
        if (!dynNames[m[1]]) {
          errors.push('R11：' + (c.name || c.cellType) + ' 引用了未定义的全局参数「' + m[1] + '」');
        }
      }
    });
    if (dynCount && !anyRef) {
      warnings.push('已定义 ' + dynCount + ' 个全局参数，但没有任何节点引用它们');
    }

    return { ok: errors.length === 0, errors: errors, warnings: warnings };
  }

  global.DslValidator = { validate: validate, isEdge: isEdge, EDGE_TYPES: EDGE_TYPES };
})(window);
