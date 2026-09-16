/* ===== 流程设计器 =====
 * 依赖：NodeDefs / DslValidator / Store / SchemaUtil / UI
 * 界面参考：node-red（分类调色板）、扣子（图标节点卡片）、jvs-logic（属性面板）
 * 导出全局 Designer
 */
(function (global) {
  'use strict';

  var NODE_H_EST = 62;   // 节点高度估算（用于连线端点计算）

  var Designer = {
    model: null,
    instId: '',          // 当前编排所属实例
    selectedId: null,
    scale: 1,
    pan: { x: 0, y: 0 },
    _drag: null,
    _linking: null,
    _panning: null,
    _collapsed: {},      // 分类折叠状态

    /* ---------- 初始化 ---------- */
    init: function () {
      this.canvas = document.getElementById('canvas');
      this.nodesLayer = document.getElementById('nodesLayer');
      this.edgesLayer = document.getElementById('edgesLayer');
      this.edgesGroup = document.getElementById('edgesGroup');
      this.tempGroup = document.getElementById('tempEdgeGroup');
      this.propBody = document.getElementById('propBody');
      this.propTitle = document.getElementById('propTitle');
      this.propSub = document.getElementById('propSub');
      this.propIcon = document.getElementById('propIcon');
      this.zoomLabel = document.getElementById('zoomLabel');
      this.emptyBox = document.getElementById('canvasEmpty');

      /* 注意：DOM 引用必须在 bindXXX 之前全部就绪，
         否则 bindCanvas 内部读不到元素会抛异常，导致后续初始化被中断。 */
      this.bindCatalog();
      this.bindCanvas();
      this.bindToolbar();
      this.bindKeyboard();

      // 默认折叠「连线」与「补偿算子」，聚焦常用节点
      this._collapsed.edge = true;
      this._collapsed.compensate = true;
      this.renderCatalog();

      // 编排内容由 App.openDesigner() 注入，这里只初始化空模型
      this.model = { cells: [], groups: [], dynamicGlobalParameters: [] };
    },

    /* ---------- 模型读写 ---------- */
    /** 加载某实例的编排（实例是编排的主体） */
    loadInstance: function (inst) {
      this.instId = inst.id;
      this.model = {
        cells: JSON.parse(JSON.stringify(inst.cells || [])),
        groups: JSON.parse(JSON.stringify(inst.groups || [])),
        dynamicGlobalParameters: []
      };
      this.selectedId = null;
      this.clearSizeCache();
      document.getElementById('planIdInput').value = inst.planId || '';
      this.renderCatalog();
      this.render();
      this.renderProps();

      /* 回写一次：Store 在保存时会清洗历史脏数据（如无效连线），
         使修复结果持久化，避免每次打开都重复处理。 */
      this.persist();
    },

    setModel: function (model) {
      this.model = model;
      this.selectedId = null;
      this.render();
      this.renderProps();
    },

    /** 保存编排回实例 */
    persist: function () {
      if (!this.model || !this.instId) return;
      var inst = Store.getInstance(this.instId);
      if (!inst) return;
      var dsl = Store.toDSL(this.model);
      inst.cells = dsl.cells;
      inst.groups = dsl.groups;
      Store.saveInstance(inst);
      this.updateStatus();
    },

    getCell: function (id) {
      return (this.model.cells || []).filter(function (c) { return c.id === id; })[0] || null;
    },
    getNodes: function () {
      return (this.model.cells || []).filter(function (c) { return !DslValidator.isEdge(c.cellType); });
    },
    getEdges: function () {
      return (this.model.cells || []).filter(function (c) { return DslValidator.isEdge(c.cellType); });
    },

    /* ---------- 节点调色板（分类折叠 + 搜索） ---------- */
    renderCatalog: function (keyword) {
      var box = document.getElementById('nodeCatalog');
      var kw = (keyword || '').trim().toLowerCase();
      var groups = NodeDefs.byCategory();
      var html = '';
      var totalHit = 0;

      groups.forEach(function (g) {
        var items = g.nodes.filter(function (n) {
          if (!kw) return true;
          return n.name.toLowerCase().indexOf(kw) >= 0
            || n.cellType.toLowerCase().indexOf(kw) >= 0
            || (n.desc || '').toLowerCase().indexOf(kw) >= 0;
        });
        if (!items.length) return;
        totalHit += items.length;

        var collapsed = !kw && Designer._collapsed[g.key] ? ' collapsed' : '';
        html += '<div class="cat' + collapsed + '" data-cat="' + g.key + '">';
        html += '<div class="cat-head" title="' + esc(g.meta.desc) + '">'
          + '<span class="cat-caret">▼</span>'
          + '<span class="cat-dot" style="background:' + g.meta.color + '"></span>'
          + '<span class="cat-name">' + esc(g.meta.name) + '</span>'
          + '<span class="cat-count">' + items.length + '</span>'
          + '</div>';
        html += '<div class="cat-body">';
        items.forEach(function (n) {
          /* 算子节点用 op:<id> 作为拖拽载荷；内置节点直接用 cellType */
          var payload = n.opId ? ('op:' + n.opId) : n.cellType;
          var code = n.opId ? (n.opType + ' · ' + n.opId.slice(0, 6)) : n.cellType;
          html += '<div class="pitem" draggable="true" data-payload="' + esc(payload) + '"'
            + ' data-builtin="' + (n.builtin ? 1 : 0) + '"'
            + ' title="' + esc(n.desc) + '">'
            + '<span class="pitem-ico" style="background:' + g.meta.color + '">' + esc(n.icon) + '</span>'
            + '<span class="pitem-text">'
            + '<span class="pitem-name">' + esc(n.name) + '</span>'
            + '<span class="pitem-code">' + esc(code) + '</span>'
            + '</span></div>';
        });
        html += '</div></div>';
      });

      if (!totalHit) {
        html = '<div class="empty-hint" style="padding:24px 12px">未找到匹配节点</div>';
      }
      box.innerHTML = html;
    },

    bindCatalog: function () {
      var self = this;

      // 折叠切换
      document.getElementById('nodeCatalog').addEventListener('click', function (e) {
        var head = e.target.closest('.cat-head');
        if (!head) return;
        var cat = head.parentNode;
        var key = cat.dataset.cat;
        cat.classList.toggle('collapsed');
        self._collapsed[key] = cat.classList.contains('collapsed');
      });

      // 拖拽
      document.getElementById('nodeCatalog').addEventListener('dragstart', function (e) {
        var item = e.target.closest('.pitem');
        if (!item) return;
        e.dataTransfer.setData('text/plain', item.dataset.payload);
        e.dataTransfer.effectAllowed = 'copy';
      });

      // 搜索
      var search = document.getElementById('paletteSearch');
      search.addEventListener('input', function () { self.renderCatalog(this.value); });

      // 折叠/展开全部
      document.getElementById('btnCollapseAll').onclick = function () {
        Object.keys(NodeDefs.CATS).forEach(function (k) { self._collapsed[k] = true; });
        self.renderCatalog(search.value);
      };
      document.getElementById('btnExpandAll').onclick = function () {
        self._collapsed = {};
        self.renderCatalog(search.value);
      };
    },

    /* ---------- 画布交互 ---------- */
    bindCanvas: function () {
      var self = this;

      this.canvas.addEventListener('dragover', function (e) { e.preventDefault(); });
      this.canvas.addEventListener('drop', function (e) {
        e.preventDefault();
        var raw = e.dataTransfer.getData('text/plain');
        if (!raw) return;

        /* 调色板项可能是：内置 cellType，或 op:<算子id> */
        var cellType, opId = '';
        if (raw.indexOf('op:') === 0) {
          cellType = NodeDefs.OP_CELLTYPE;
          opId = raw.slice(3);
        } else {
          cellType = raw;
        }

        var def = NodeDefs.get(cellType);
        if (DslValidator.isEdge(cellType)) {
          UI.toast('连线不能直接拖入画布，请从节点右侧圆点拖出', 'warn');
          return;
        }
        if (def.fixed && self.getNodes().some(function (n) { return n.cellType === cellType; })) {
          UI.toast('「' + def.name + '」在画布中只能有一个', 'warn');
          return;
        }
        var pt = self.toCanvasPoint(e.clientX, e.clientY);
        self.addNode(cellType, Math.round(pt.x - 80), Math.round(pt.y - 32), opId);
      });

      this.canvas.addEventListener('mousedown', function (e) {
        if (e.target.closest && e.target.closest('.node')) return;
        if (e.button === 1 || e.button === 2) {
          self._panning = { sx: e.clientX, sy: e.clientY, ox: self.pan.x, oy: self.pan.y };
          e.preventDefault();
          return;
        }
        self.select(null);
      });

      window.addEventListener('mousemove', function (e) {
        if (self._panning) {
          self.pan.x = self._panning.ox + (e.clientX - self._panning.sx);
          self.pan.y = self._panning.oy + (e.clientY - self._panning.sy);
          self.applyTransform();
          return;
        }
        if (self._drag) self.onNodeDrag(e);
        if (self._linking) self.onLinking(e);
      });

      window.addEventListener('mouseup', function (e) {
        if (self._panning) { self._panning = null; return; }
        if (self._drag) {
          var dragged = self.getCell(self._drag.id);
          self._drag = null;
          var el = self.nodesLayer.querySelector('.node.dragging');
          if (el) el.classList.remove('dragging');
          /* 松手时吸附到网格，并同步 DOM 位置 */
          if (dragged) {
            self.snapNode(dragged);
            var del = self.nodesLayer.querySelector('.node[data-id="' + dragged.id + '"]');
            if (del) { del.style.left = dragged.x + 'px'; del.style.top = dragged.y + 'px'; }
            self.renderEdges();
          }
          self.persist();
          return;
        }
        if (self._linking) self.onLinkEnd(e);
      });

      /* 窗口失焦 / ESC：中断连线手势，避免临时预览线残留 */
      window.addEventListener('blur', function () { self.cancelLink(); });
      document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' || e.keyCode === 27) { self.cancelLink(); self.hideCtxMenu(); }
      });

      /* 右键：命中节点/连线则弹出上下文菜单，否则仅屏蔽默认菜单 */
      this.canvas.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        var hit = self.hitTest(e.target);
        if (!hit) { self.hideCtxMenu(); return; }
        self.select(hit.id);
        self.showCtxMenu(e.clientX, e.clientY, hit);
      });

      /* 双击节点：打开编辑（聚焦属性面板的名称输入框） */
      this.nodesLayer.addEventListener('dblclick', function (e) {
        var nodeEl = e.target.closest && e.target.closest('.node');
        if (!nodeEl) return;
        e.preventDefault();
        self.select(nodeEl.dataset.id);
        self.focusPropEditor();
      });

      /* 双击连线：选中并聚焦其属性编辑 */
      this.edgesLayer.addEventListener('dblclick', function (e) {
        var hit = self.hitTest(e.target);
        if (!hit || hit.kind !== 'edge') return;
        e.preventDefault();
        self.select(hit.id);
        self.focusPropEditor();
      });

      /* 点击任意处关闭右键菜单 */
      document.addEventListener('mousedown', function (e) {
        if (!e.target.closest || !e.target.closest('#ctxMenu')) self.hideCtxMenu();
      });
      window.addEventListener('blur', function () { self.hideCtxMenu(); });
      this.canvas.addEventListener('wheel', function () { self.hideCtxMenu(); }, { passive: true });

      this.canvas.addEventListener('wheel', function (e) {
        if (!e.ctrlKey && !e.metaKey) return;
        e.preventDefault();
        self.setScale(self.scale + (e.deltaY > 0 ? -0.1 : 0.1));
      }, { passive: false });
    },

    bindToolbar: function () {
      var self = this;
      document.getElementById('btnZoomIn').onclick = function () { self.setScale(self.scale + 0.1); };
      document.getElementById('btnZoomOut').onclick = function () { self.setScale(self.scale - 0.1); };
      document.getElementById('btnFit').onclick = function () { self.fit(); };
      document.getElementById('btnClear').onclick = function () {
        UI.confirmAction('确定清空当前画布？此操作不可撤销。', function () {
          self.model = Store.createBlank(document.getElementById('planIdInput').value.trim() || 'PLAN_2026_001');
          self.selectedId = null;
          self.persist();
          self.render();
          self.renderProps();
          UI.toast('画布已清空', 'ok');
        });
      };
      document.getElementById('planIdInput').addEventListener('change', function () { self.persist(); });
    },

    bindKeyboard: function () {
      var self = this;
      document.addEventListener('keydown', function (e) {
        var tag = (e.target.tagName || '').toLowerCase();
        if (tag === 'input' || tag === 'textarea' || tag === 'select') return;
        if (e.key === 'Delete' || e.key === 'Backspace') {
          if (self.selectedId) { e.preventDefault(); self.removeCell(self.selectedId); }
        }
        if (e.key === 'Escape') { self.cancelLink(); self.select(null); }
      });
    },

    /* ---------- 坐标换算 ---------- */
    toCanvasPoint: function (clientX, clientY) {
      var rect = this.canvas.getBoundingClientRect();
      return {
        x: (clientX - rect.left - this.pan.x) / this.scale,
        y: (clientY - rect.top - this.pan.y) / this.scale
      };
    },

    applyTransform: function () {
      this.nodesLayer.style.transform =
        'translate(' + this.pan.x + 'px,' + this.pan.y + 'px) scale(' + this.scale + ')';
      this.edgesGroup.setAttribute('transform',
        'translate(' + this.pan.x + ',' + this.pan.y + ') scale(' + this.scale + ')');
      this.zoomLabel.textContent = Math.round(this.scale * 100) + '%';
    },

    setScale: function (s) {
      this.scale = Math.max(0.3, Math.min(2, Math.round(s * 10) / 10));
      this.applyTransform();
      this.renderEdges();
    },

    fit: function () {
      var self = this;
      var nodes = this.getNodes();
      if (!nodes.length) { this.pan = { x: 0, y: 0 }; this.setScale(1); return; }

      /* 用实测尺寸包围盒，避免估值偏大导致缩放过小 */
      var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      nodes.forEach(function (n) {
        if (!isFinite(n.x)) n.x = 0;
        if (!isFinite(n.y)) n.y = 0;
        var sz = self.nodeSize(n.id);
        minX = Math.min(minX, n.x);
        minY = Math.min(minY, n.y);
        maxX = Math.max(maxX, n.x + sz.w);
        maxY = Math.max(maxY, n.y + sz.h);
      });

      var rect = this.canvas.getBoundingClientRect();
      var pad = 64;
      var spanX = Math.max(maxX - minX, 1);
      var spanY = Math.max(maxY - minY, 1);
      var sx = (rect.width - pad * 2) / spanX;
      var sy = (rect.height - pad * 2) / spanY;
      if (!isFinite(sx) || !isFinite(sy)) { this.pan = { x: 0, y: 0 }; this.setScale(1); return; }
      this.scale = Math.max(0.4, Math.min(1, Math.min(sx, sy)));
      this.pan = {
        x: pad - minX * this.scale + (rect.width - pad * 2 - spanX * this.scale) / 2,
        y: pad - minY * this.scale + (rect.height - pad * 2 - spanY * this.scale) / 2
      };
      this.applyTransform();
      this.clearSizeCache();
      this.renderEdges();
      this.zoomLabel.textContent = Math.round(this.scale * 100) + '%';
    },

    /* ---------- 增删改 ---------- */
    addNode: function (cellType, x, y, opId) {
      var def = NodeDefs.get(cellType);
      var id = Store.uid();
      var data = def.defaultData();

      /* 算子节点：绑定具体的算子。
         注意：算子引用必须放在 cell.data 下（与后端 DSL 及 nodePresentation
         的读取路径一致），否则节点会渲染成「未绑定算子」。 */
      var name = def.name;
      if (cellType === NodeDefs.OP_CELLTYPE) {
        var op = opId ? Store.getOperator(opId) : null;
        data = { opId: opId || '', opType: (op && op.opType) || 'http' };
        name = op ? op.name : '未绑定算子';
      }

      var cell = Object.assign({}, data, {
        id: id,
        cellType: cellType,
        name: name,
        x: x, y: y,
        ports: { items: [{ id: id + '_out' }, { id: id + '_in' }] }
      });

      /* 普通节点的业务字段平铺在 cell 上；算子节点的引用统一收敛到 data 下 */
      if (cellType === NodeDefs.OP_CELLTYPE) {
        cell.data = { opId: data.opId, opType: data.opType };
        delete cell.opId;
        delete cell.opType;
      }

      /* 网格吸附 + 避让已有节点，避免重叠堆在一起 */
      this.snapNode(cell);
      this.avoidOverlap(cell);

      this.model.cells.push(cell);
      this.persist();
      this.render();
      this.select(id);
      return cell;
    },

    /**
     * 若新节点与已有节点重叠，则向下（或向右）顺移至空位。
     * 采用简单的环形探测：每次下移一个行距，最多尝试 20 次。
     */
    avoidOverlap: function (cell) {
      var self = this;
      var others = this.getNodes().filter(function (n) { return n.id !== cell.id; });
      if (!others.length) return;

      var size = { w: 168, h: 76 };
      var GAP_X = 40, GAP_Y = 28;

      function overlaps(ax, ay) {
        return others.some(function (o) {
          var os = self.nodeSize(o.id);
          var ow = os.provisional ? size.w : os.w;
          var oh = os.provisional ? size.h : os.h;
          return !(ax + size.w + GAP_X <= o.x ||
                   o.x + ow + GAP_X <= ax ||
                   ay + size.h + GAP_Y <= o.y ||
                   o.y + oh + GAP_Y <= ay);
        });
      }

      var tryY = cell.y;
      var guard = 0;
      while (overlaps(cell.x, tryY) && guard++ < 20) {
        tryY += size.h + GAP_Y;
      }
      if (guard >= 20) {
        /* 下方排满则向右另起一列 */
        cell.x += size.w + GAP_X * 2;
        cell.y = 90;
        this.snapNode(cell);
        return;
      }
      cell.y = tryY;
      this.snapNode(cell);
    },

    addEdge: function (sourceId, sourcePort, targetId, targetPort, cellType) {
      var def = NodeDefs.get(cellType || 'edge_common');
      var id = Store.uid();
      var edge = {
        id: id,
        cellType: def.cellType,
        name: def.name,
        source: { cell: sourceId, port: sourcePort },
        target: { cell: targetId, port: targetPort },
        data: def.defaultData()
      };
      this.model.cells.push(edge);
      this.persist();
      this.render();
      this.select(id);
      return edge;
    },

    removeCell: function (id) {
      var self = this;
      var cell = this.getCell(id);
      if (!cell) return;
      var doRemove = function () {
        self.model.cells = self.model.cells.filter(function (c) { return c.id !== id; });
        self.model.cells = self.model.cells.filter(function (c) {
          if (!DslValidator.isEdge(c.cellType)) return true;
          return !(c.source && c.source.cell === id) && !(c.target && c.target.cell === id);
        });
        self.selectedId = null;
        self.persist();
        self.render();
        self.renderProps();
        UI.toast('已删除', 'ok');
      };
      if (DslValidator.isEdge(cell.cellType)) {
        doRemove();
      } else {
        UI.confirmAction('确定删除节点「' + (cell.name || cell.cellType) + '」及其相连的线？', doRemove);
      }
    },

    updateData: function (id, path, value) {
      var cell = this.getCell(id);
      if (!cell) return;
      if (!cell.data) cell.data = {};
      if (path.indexOf('.') < 0) {
        cell.data[path] = value;
      } else {
        var parts = path.split('.');
        var obj = cell.data;
        for (var i = 0; i < parts.length - 1; i++) {
          if (!obj[parts[i]] || typeof obj[parts[i]] !== 'object') obj[parts[i]] = {};
          obj = obj[parts[i]];
        }
        obj[parts[parts.length - 1]] = value;
      }
      this.persist();
      this.renderEdges();
    },

    /* ---------- 命中测试 ---------- */
    /**
     * 判断鼠标位置命中了哪个画布元素。
     * @returns {{id, kind:'node'|'edge', cell}|null}
     */
    hitTest: function (targetEl) {
      if (!targetEl || !targetEl.closest) return null;

      var nodeEl = targetEl.closest('.node');
      if (nodeEl) {
        var cell = this.getCell(nodeEl.dataset.id);
        return cell ? { id: cell.id, kind: 'node', cell: cell } : null;
      }
      var edgeEl = targetEl.closest('.edge-hit');
      if (edgeEl) {
        var eid = edgeEl.getAttribute('data-id');
        var ec = eid ? this.getCell(eid) : null;
        return ec ? { id: ec.id, kind: 'edge', cell: ec } : null;
      }
      return null;
    },

    /* ---------- 右键菜单 ---------- */
    showCtxMenu: function (clientX, clientY, hit) {
      var self = this;
      var menu = document.getElementById('ctxMenu');
      var cell = hit.cell;
      var items = [];

      if (hit.kind === 'node') {
        items.push({ label: '编辑', icon: '✎', act: 'edit' });
        items.push({ label: '重命名', icon: 'T', act: 'rename' });
        items.push({ sep: true });
        items.push({ label: '复制节点', icon: '⧉', act: 'duplicate' });
        if (cell.cellType !== 'node_start' && cell.cellType !== 'node_end') {
          items.push({ label: '加入补偿组', icon: '⟲', act: 'group' });
        }
        items.push({ sep: true });
        items.push({ label: '删除节点', icon: '✕', act: 'delete', danger: true });
      } else {
        items.push({ label: '编辑线属性', icon: '✎', act: 'edit' });
        items.push({ sep: true });
        items.push({ label: '置为普通线', icon: '→', act: 'type:edge_common' });
        items.push({ label: '置为决策线', icon: '◇', act: 'type:edge_decision' });
        items.push({ label: '置为循环线', icon: '↻', act: 'type:edge_loop' });
        items.push({ label: '置为补偿线', icon: '⟲', act: 'type:edge_compensate' });
        items.push({ sep: true });
        items.push({ label: '反向连线', icon: '⇄', act: 'reverse' });
        items.push({ label: '删除连线', icon: '✕', act: 'delete', danger: true });
      }

      menu.innerHTML = items.map(function (it) {
        if (it.sep) return '<div class="ctx-sep"></div>';
        return '<button class="ctx-item' + (it.danger ? ' danger' : '') + '" data-act="' + it.act + '">'
          + '<span class="ctx-ico">' + it.icon + '</span>'
          + '<span>' + esc(it.label) + '</span>'
          + '</button>';
      }).join('');

      /* 先显示以获取尺寸，再修正边界位置 */
      menu.classList.add('open');
      var mw = menu.offsetWidth;
      var mh = menu.offsetHeight;
      var vw = window.innerWidth;
      var vh = window.innerHeight;
      var x = clientX;
      var y = clientY;
      if (x + mw > vw - 8) x = vw - mw - 8;
      if (y + mh > vh - 8) y = vh - mh - 8;
      menu.style.left = Math.max(8, x) + 'px';
      menu.style.top = Math.max(8, y) + 'px';

      menu.querySelectorAll('[data-act]').forEach(function (btn) {
        btn.onclick = function () {
          var act = btn.dataset.act;
          self.hideCtxMenu();
          self.handleCtxAction(act, hit);
        };
      });
    },

    hideCtxMenu: function () {
      var menu = document.getElementById('ctxMenu');
      if (menu) menu.classList.remove('open');
    },

    handleCtxAction: function (act, hit) {
      var self = this;
      var cell = hit.cell;

      /* 线型切换 */
      if (act.indexOf('type:') === 0) {
        var newType = act.slice(5);
        cell.cellType = newType;
        cell.name = NodeDefs.get(newType).name;
        cell.data = NodeDefs.get(newType).defaultData();
        this.persist();
        this.render();
        this.select(cell.id);
        UI.toast('已切换为「' + NodeDefs.get(newType).name + '」', 'ok');
        return;
      }

      switch (act) {
        case 'edit':
          this.select(cell.id);
          this.focusPropEditor();
          break;
        case 'rename':
          this.select(cell.id);
          UI.modal('重命名', cell.name || '', {
            okText: '确定',
            onOk: function (val) {
              var v = (val || '').trim();
              if (!v) { UI.toast('名称不能为空', 'warn'); return false; }
              cell.name = v;
              self.persist();
              self.clearSizeCache(cell.id);
              self.render();
              self.renderProps();
              UI.toast('已重命名', 'ok');
            }
          });
          break;
        case 'duplicate':
          this.duplicateNode(cell);
          break;
        case 'group':
          this.joinGroup(cell);
          break;
        case 'reverse':
          var s = cell.source;
          cell.source = cell.target;
          cell.target = s;
          this.persist();
          this.render();
          this.select(cell.id);
          UI.toast('已反向', 'ok');
          break;
        case 'delete':
          this.removeCell(cell.id);
          break;
      }
    },

    /** 复制节点（连线不复制） */
    duplicateNode: function (cell) {
      var copy = JSON.parse(JSON.stringify(cell));
      copy.id = Store.uid();
      copy.name = (cell.name || '') + ' 副本';
      copy.x = cell.x + 30;
      copy.y = cell.y + 30;
      copy.ports = { items: [{ id: copy.id + '_out' }, { id: copy.id + '_in' }] };
      if (copy.groupIds) delete copy.groupIds;

      this.snapNode(copy);
      this.avoidOverlap(copy);
      this.model.cells.push(copy);
      this.persist();
      this.render();
      this.select(copy.id);
      UI.toast('已复制节点', 'ok');
    },

    /** 选中元素后把焦点移到属性面板，便于直接编辑 */
    focusPropEditor: function () {
      var body = this.propBody;
      var first = body.querySelector('input[data-bind="name"]')
        || body.querySelector('input[type=text]:not([readonly])')
        || body.querySelector('textarea');
      if (first) {
        first.focus();
        if (first.select) { try { first.select(); } catch (e) {} }
      }
    },

    /* ---------- 选中 ---------- */
    select: function (id) {
      this.selectedId = id;
      this.renderNodes();
      this.renderEdges();
      this.renderProps();
      this.updateStatus();
    },

    /* ---------- 渲染 ---------- */
    /**
     * 渲染时序：
     *   1. 先渲染节点（DOM 插入）
     *   2. 等一帧让浏览器完成布局，此时 offsetWidth/offsetHeight 才有值
     *   3. 再渲染连线（依赖真实尺寸计算端点）
     * 若在第 2 步之前渲染连线，会因读不到尺寸而用估值，导致连线错位。
     */
    render: function () {
      /* 任何重绘都意味着当前的连线手势作废，先清掉临时预览线，
         否则被中断的拖拽会在画布上留下一条永远不消失的虚线。 */
      this.clearTempEdge();
      this.renderNodes();
      this.applyTransform();
      this.updateStatus();

      /* 强制同步布局后再画线：读取一次 offsetHeight 触发 reflow */
      void this.nodesLayer.offsetHeight;
      this.clearSizeCache();
      this.renderEdges();

      /* 下一帧再画一次，兜底处理字体/图标异步加载导致的尺寸变化 */
      var self = this;
      if (this._rafId) cancelAnimationFrame(this._rafId);
      this._rafId = requestAnimationFrame(function () {
        self.clearSizeCache();
        self.renderEdges();
      });
    },

    renderNodes: function () {
      var self = this;
      var nodes = this.getNodes();
      var html = nodes.map(function (n) {
        var ps = self.nodePresentation(n);
        var sel = n.id === self.selectedId ? ' selected' : '';
        /* 坐标兜底：历史数据可能缺 x/y，直接拼进 style 会得到 "undefinedpx" */
        if (!isFinite(n.x)) n.x = 0;
        if (!isFinite(n.y)) n.y = 0;
        return '<div class="node' + sel + '" data-id="' + n.id + '" '
          + 'style="left:' + n.x + 'px;top:' + n.y + 'px">'
          + '<div class="node-accent" style="background:' + ps.color + '"></div>'
          + '<div class="node-main">'
          + '<span class="node-ico" style="background:' + ps.color + '">' + esc(ps.icon) + '</span>'
          + '<span class="node-meta">'
          + '<span class="node-name">' + esc(ps.title) + '</span>'
          + '<span class="node-type">' + esc(ps.subtitle) + '</span>'
          + '</span></div>'
          + ps.badges
          + '<span class="node-port in" data-port="' + n.id + '_in" data-node="' + n.id + '" title="输入端点"></span>'
          + '<span class="node-port out" data-port="' + n.id + '_out" data-node="' + n.id + '" title="拖拽以连线"></span>'
          + '</div>';
      }).join('');
      this.nodesLayer.innerHTML = html;

      if (this.emptyBox) this.emptyBox.classList.toggle('show', nodes.length === 0);

      /* 上一帧标记为拖拽中的节点，重建 DOM 后补回 dragging 类 */
      if (this._drag) {
        var dragEl = this.nodesLayer.querySelector('.node[data-id="' + this._drag.id + '"]');
        if (dragEl) dragEl.classList.add('dragging');
      }

      this.nodesLayer.querySelectorAll('.node').forEach(function (el) {
        el.addEventListener('mousedown', function (e) {
          if (e.target.classList.contains('node-port')) return;
          var id = el.dataset.id;
          var cell = self.getCell(id);
          if (!cell) return;

          /* 先记录拖拽态，再 select()。
             select() 内部会 renderNodes() 重建整个节点层，
             因此不能在 select() 之后对旧元素加 class —— 那会作用到已被丢弃的节点上。 */
          self._drag = { id: id, sx: e.clientX, sy: e.clientY, ox: cell.x, oy: cell.y };
          self.select(id);

          var fresh = self.nodesLayer.querySelector('.node[data-id="' + id + '"]');
          if (fresh) fresh.classList.add('dragging');

          e.preventDefault();
        });
      });

      this.nodesLayer.querySelectorAll('.node-port.out').forEach(function (el) {
        el.addEventListener('mousedown', function (e) {
          e.stopPropagation();
          self._linking = {
            sourceId: el.dataset.node,
            sourcePort: el.dataset.port,
            x1: 0, y1: 0, x2: 0, y2: 0
          };
          var p = self.portCenter(el.dataset.node, true);
          self._linking.x1 = p.x; self._linking.y1 = p.y;
          self._linking.x2 = p.x; self._linking.y2 = p.y;
          e.preventDefault();
        });
      });
    },

    /**
     * 统一计算节点的展示信息（图标 / 颜色 / 标题 / 副标题 / 徽标）。
     * 算子节点（node_op）会解析出所引用算子的名称与类型。
     */
    nodePresentation: function (n) {
      var def = NodeDefs.get(n.cellType);
      var isOp = NodeDefs.isOperatorNode(n.cellType);
      var op = isOp && n.data && n.data.opId ? Store.getOperator(n.data.opId) : null;

      var color, icon, title, subtitle;
      if (isOp) {
        var t = (op && op.opType) || (n.data && n.data.opType) || 'http';
        var meta = Store.OP_TYPES[t] || Store.OP_TYPES.http;
        color = meta.color;
        icon = meta.icon;
        title = n.name && n.name !== '算子节点' ? n.name : (op ? op.name : '未绑定算子');
        subtitle = op ? (t + ' · ' + op.id.slice(0, 6)) : (t + ' · 算子已删除');
      } else {
        color = NodeDefs.categoryOf(n.cellType).color;
        icon = def.icon;
        title = n.name || def.name;
        subtitle = n.cellType;
      }

      var badges = [];

      /* 算子节点：显示类型/地址等摘要 */
      if (isOp && op) {
        if (op.opType === 'http') {
          badges.push('<span class="nbadge">' + esc((op.method || 'GET')) + '</span>');
        } else if (op.opType === 'sql') {
          badges.push('<span class="nbadge op">' + esc(shortDb(op.database)) + '</span>');
        } else if (op.opType === 'shell' || op.opType === 'execShell') {
          badges.push('<span class="nbadge op">' + esc(shortDb(op.env)) + '</span>');
        } else if (op.builtin) {
          badges.push('<span class="nbadge inner">内置</span>');
        }
      } else if (isOp) {
        badges.push('<span class="nbadge warn">算子缺失</span>');
      }

      /* 通用徽标 */
      if (n.cellType === 'node_start') badges.push('<span class="nbadge">入口</span>');
      if (n.cellType === 'node_end') badges.push('<span class="nbadge">出口</span>');
      if (n.groupIds && n.groupIds.length) badges.push('<span class="nbadge comp">补偿组</span>');
      if (n.cellType === 'node_inner_sleep' && n.data && n.data.milliseconds) {
        badges.push('<span class="nbadge">' + n.data.milliseconds + 'ms</span>');
      }

      return {
        color: color, icon: icon, title: title, subtitle: subtitle,
        badges: badges.length ? '<div class="node-badges">' + badges.join('') + '</div>' : ''
      };
    },

    onNodeDrag: function (e) {
      var d = this._drag;
      var cell = this.getCell(d.id);
      if (!cell) return;
      var scale = isFinite(this.scale) && this.scale > 0 ? this.scale : 1;
      var ox = isFinite(d.ox) ? d.ox : 0;
      var oy = isFinite(d.oy) ? d.oy : 0;
      cell.x = Math.round(ox + (e.clientX - d.sx) / scale);
      cell.y = Math.round(oy + (e.clientY - d.sy) / scale);
      var el = this.nodesLayer.querySelector('.node[data-id="' + d.id + '"]');
      if (el) { el.style.left = cell.x + 'px'; el.style.top = cell.y + 'px'; }
      /* 位置变化不影响尺寸缓存，直接重画连线即可 */
      this.renderEdges();
    },

    /** 拖动结束时把坐标吸附到 10px 网格，保持画面整齐 */
    snapNode: function (cell) {
      if (!isFinite(cell.x)) cell.x = 0;
      if (!isFinite(cell.y)) cell.y = 0;
      cell.x = Math.round(cell.x / 10) * 10;
      cell.y = Math.round(cell.y / 10) * 10;
    },

    onLinking: function (e) {
      var pt = this.toCanvasPoint(e.clientX, e.clientY);
      var l = this._linking;
      l.x2 = pt.x; l.y2 = pt.y;
      this.drawTempEdge(l);
      this.nodesLayer.querySelectorAll('.node').forEach(function (el) {
        el.classList.toggle('connectable', el.dataset.id !== l.sourceId);
      });
    },

    onLinkEnd: function (e) {
      var l = this._linking;
      this._linking = null;
      this.clearTempEdge();
      this.nodesLayer.querySelectorAll('.node').forEach(function (el) { el.classList.remove('connectable'); });

      var targetEl = document.elementFromPoint(e.clientX, e.clientY);
      var nodeEl = targetEl && targetEl.closest ? targetEl.closest('.node') : null;
      if (!nodeEl) return;
      var targetId = nodeEl.dataset.id;
      if (targetId === l.sourceId) { UI.toast('不能连接到自身', 'warn'); return; }

      var targetCell = this.getCell(targetId);
      if (targetCell.cellType === 'node_start') { UI.toast('开始节点不能作为连线终点', 'warn'); return; }
      var sourceCell = this.getCell(l.sourceId);
      if (sourceCell.cellType === 'node_end') { UI.toast('结束节点不能有出线', 'warn'); return; }

      var dup = this.getEdges().some(function (ed) {
        return ed.source.cell === l.sourceId && ed.target.cell === targetId;
      });
      if (dup) { UI.toast('该连接已存在', 'warn'); return; }

      this.addEdge(l.sourceId, l.sourcePort, targetId, targetId + '_in', 'edge_common');
      UI.toast('连线已创建，可在右侧面板切换线型', 'ok');
    },

    cancelLink: function () {
      if (this._linking) { this._linking = null; this.clearTempEdge(); }
    },

    /* ---------- 连线渲染 ---------- */
    renderEdges: function () {
      var self = this;
      var svg = this.edgesGroup;
      var ns = 'http://www.w3.org/2000/svg';
      while (svg.firstChild) svg.removeChild(svg.firstChild);

      this.getEdges().forEach(function (edge) {
        var s = self.portCenter(edge.source && edge.source.cell, true);
        var t = self.portCenter(edge.target && edge.target.cell, false);
        if (!s || !t) return;
        var d = self.bezier(s, t);

        var hit = document.createElementNS(ns, 'path');
        hit.setAttribute('d', d);
        hit.setAttribute('class', 'edge-hit');
        hit.setAttribute('data-id', edge.id);   /* 供右键菜单与命中测试定位 */
        hit.addEventListener('mousedown', function (e) {
          /* 右键交给 contextmenu 处理，避免选中后被立即清空 */
          if (e.button === 2) return;
          e.stopPropagation();
          self.select(edge.id);
        });
        svg.appendChild(hit);

        var path = document.createElementNS(ns, 'path');
        path.setAttribute('d', d);
        path.setAttribute('class', 'edge-path ' + edge.cellType + (self.selectedId === edge.id ? ' selected' : ''));
        svg.appendChild(path);

        var label = self.edgeLabel(edge);
        if (label) {
          var text = document.createElementNS(ns, 'text');
          text.setAttribute('class', 'edge-label');
          text.setAttribute('x', (s.x + t.x) / 2);
          text.setAttribute('y', (s.y + t.y) / 2 - 7);
          text.setAttribute('text-anchor', 'middle');
          text.textContent = label;
          svg.appendChild(text);
        }
      });
    },

    edgeLabel: function (edge) {
      if (edge.cellType === 'edge_decision') {
        var e = edge.data && edge.data.jsonpathElexpression;
        return e ? '◇ ' + trunc(e, 20) : '◇ 决策';
      }
      if (edge.cellType === 'edge_loop') {
        var d = edge.data || {};
        return '↻ ' + (d.loopType || 'doWhile')
          + (d.jsonpathElexpression ? ' · ' + trunc(d.jsonpathElexpression, 12) : '');
      }
      if (edge.cellType === 'edge_compensate') return '⟲ 补偿';
      return '';
    },

    /**
     * 计算桩点中心坐标。
     *
     * 关键：节点尺寸必须实测（offsetWidth/offsetHeight），不能依赖硬编码估值，
     *      否则节点高度随徽标数量变化时连线会飘出节点外。
     *      测量结果缓存在 _sizeCache 中，避免同一帧内反复触发布局计算。
     */
    portCenter: function (nodeId, isOut) {
      var cell = this.getCell(nodeId);
      if (!cell) return null;

      var size = this.nodeSize(nodeId);
      return {
        x: cell.x + (isOut ? size.w : 0),
        y: cell.y + size.h / 2
      };
    },

    /** 实测节点尺寸（带缓存与兜底） */
    nodeSize: function (nodeId) {
      this._sizeCache = this._sizeCache || {};
      var cached = this._sizeCache[nodeId];
      if (cached && cached.w > 0 && cached.h > 0) return cached;

      var el = this.nodesLayer.querySelector('.node[data-id="' + nodeId + '"]');
      var w = 0, h = 0;
      if (el) {
        w = el.offsetWidth;
        h = el.offsetHeight;
      }
      /* offsetWidth 为 0 说明尚未完成布局，先返回估值但不写缓存 */
      if (!w || !h) return { w: 168, h: NODE_H_EST, provisional: true };

      var size = { w: w, h: h };
      this._sizeCache[nodeId] = size;
      return size;
    },

    clearSizeCache: function (nodeId) {
      if (!this._sizeCache) return;
      if (nodeId) delete this._sizeCache[nodeId];
      else this._sizeCache = {};
    },

    /**
     * 三次贝塞尔连线。
     * 控制点水平偏移量取两点水平距离的 45%，但设下限与上限，
     * 避免短距离时曲线过陡、长距离时过于平缓。
     */
    bezier: function (s, t) {
      var dx = Math.abs(t.x - s.x);
      var ctrl = Math.min(Math.max(dx * 0.45, 36), 140);
      /* 反向连线（目标在左侧）时控制点外扩，让曲线绕出可见弧线 */
      if (t.x < s.x) ctrl = Math.max(ctrl, 70);
      return 'M' + s.x + ',' + s.y + ' C' + (s.x + ctrl) + ',' + s.y + ' '
        + (t.x - ctrl) + ',' + t.y + ' ' + t.x + ',' + t.y;
    },

    drawTempEdge: function (l) {
      var ns = 'http://www.w3.org/2000/svg';
      while (this.tempGroup.firstChild) this.tempGroup.removeChild(this.tempGroup.firstChild);
      var p = document.createElementNS(ns, 'path');
      p.setAttribute('d', this.bezier({ x: l.x1, y: l.y1 }, { x: l.x2, y: l.y2 }));
      p.setAttribute('stroke', '#2563eb');
      p.setAttribute('stroke-width', '2');
      p.setAttribute('stroke-dasharray', '5 4');
      p.setAttribute('fill', 'none');
      this.tempGroup.appendChild(p);
    },

    clearTempEdge: function () {
      while (this.tempGroup.firstChild) this.tempGroup.removeChild(this.tempGroup.firstChild);
    },

    /* ---------- 状态栏 ---------- */
    updateStatus: function () {
      var nodes = this.getNodes().length;
      var edges = this.getEdges().length;
      var sn = document.getElementById('statusNodes');
      var se = document.getElementById('statusEdges');
      var ss = document.getElementById('statusSelection');
      if (sn) sn.textContent = '节点 ' + nodes;
      if (se) se.textContent = '连线 ' + edges;
      if (ss) {
        var cell = this.selectedId ? this.getCell(this.selectedId) : null;
        if (!cell) {
          ss.textContent = '未选中';
        } else {
          var def = NodeDefs.get(cell.cellType);
          ss.textContent = '已选中：' + (def.name || cell.cellType) + ' · ' + trunc(cell.id, 8);
        }
      }
    },

    /* ---------- 属性面板 ---------- */
    renderProps: function () {
      var cell = this.selectedId ? this.getCell(this.selectedId) : null;
      /* 元素节点与连线节点的定义都要取，后面「节点配置」分组依赖 def */
      var def = cell ? NodeDefs.get(cell.cellType) : null;

      if (!cell) {
        this.propIcon.textContent = '◈';
        this.propTitle.textContent = '属性面板';
        this.propSub.textContent = '未选中任何元素';
        this.propBody.innerHTML =
          '<div class="insp-empty">'
          + '<div class="ie-ico">⇦</div>'
          + '<p>选中画布中的<strong>节点</strong>或<strong>连线</strong><br>即可在此编辑其属性</p>'
          + '</div>';
        return;
      }

      var ps = this.nodePresentation(cell);
      this.propIcon.textContent = ps.icon;
      this.propIcon.style.background = ps.color;
      this.propIcon.style.color = '#fff';
      this.propTitle.textContent = ps.title;
      this.propSub.textContent = cell.cellType;

      var html = '';

      /* 基础信息 */
      html += '<div class="igroup">';
      html += '<div class="igroup-head"><span class="g-dot"></span>基础信息</div>';
      html += field('类型', '<input value="' + esc(cell.cellType) + '" readonly>');
      html += field('ID', '<input value="' + esc(cell.id) + '" readonly>');
      html += field('名称', '<input type="text" data-bind="name" value="' + esc(cell.name || '') + '">');
      html += '</div>';

      /* 算子节点：显示绑定的算子配置（只读），并提供重新绑定入口 */
      if (NodeDefs.isOperatorNode(cell.cellType)) {
        var op = cell.data && cell.data.opId ? Store.getOperator(cell.data.opId) : null;
        html += '<div class="igroup">';
        html += '<div class="igroup-head"><span class="g-dot"></span>绑定的算子</div>';

        if (!op) {
          html += '<div class="tip" style="color:var(--danger)">该算子已被删除，请在下方重新选择要绑定的算子。</div>';
        } else {
          var t = Store.OP_TYPES[op.opType] || Store.OP_TYPES.http;
          html += field('算子包类型', '<input value="' + esc(t.name + '（' + op.opType + '）') + '" readonly>');
          html += field('算子名称', '<input value="' + esc(op.name) + '" readonly>');
          if (op.opType === 'http') {
            html += field('请求', '<input value="' + esc((op.method || 'GET') + ' ' + (op.url || '')) + '" readonly>');
          } else if (op.opType === 'sql') {
            html += field('数据库', '<input value="' + esc(op.database) + '" readonly>');
            html += field('SQL 预览', '<textarea rows="4" readonly>' + esc(op.sql || '') + '</textarea>');
          } else if (op.opType === 'shell') {
            html += field('执行环境', '<input value="' + esc(op.env) + '" readonly>');
            html += field('脚本预览', '<textarea rows="4" readonly>' + esc(op.script || '') + '</textarea>');
          } else {
            var kind = (Store.OP_TYPES[op.opType] || {}).cellType || op.opType;
            html += field('内置能力', '<input value="' + esc(kind) + '" readonly>');
          }
        }

        /* 重新绑定下拉：按分组列出全部可用算子 */
        html += field('重新绑定', this.rebindSelect(cell));
        html += '<div class="tip">如需修改算子本身，请前往「算子注册」页编辑；此处仅做引用。</div>';
        html += '</div>';
      }

      /* 连线端点 */
      if (DslValidator.isEdge(cell.cellType)) {
        html += '<div class="igroup">';
        html += '<div class="igroup-head"><span class="g-dot"></span>连接关系</div>';
        html += field('起点节点', '<input value="' + esc(cell.source ? cell.source.cell : '') + '" readonly>');
        html += field('终点节点', '<input value="' + esc(cell.target ? cell.target.cell : '') + '" readonly>');
        html += field('线类型', '<select data-bind="cellType">'
          + ['edge_common', 'edge_decision', 'edge_loop', 'edge_compensate'].map(function (t) {
            return '<option value="' + t + '"' + (cell.cellType === t ? ' selected' : '') + '>'
              + NodeDefs.get(t).name + '（' + t + '）</option>';
          }).join('') + '</select>');
        html += '</div>';
      }

      /* 节点专属配置 */
      if (def.schema && def.schema.length) {
        html += '<div class="igroup">';
        html += '<div class="igroup-head"><span class="g-dot"></span>节点配置</div>';
        var self = this;
        def.schema.forEach(function (f) { html += self.renderField(cell, f); });
        html += '</div>';
      }

      /* 强组合 */
      if (!DslValidator.isEdge(cell.cellType)
        && cell.cellType !== 'node_start' && cell.cellType !== 'node_end') {
        html += '<div class="igroup">';
        html += '<div class="igroup-head"><span class="g-dot"></span>强组合（补偿组）</div>';
        html += '<div class="chip-list">';
        (cell.groupIds || []).forEach(function (gid) {
          var g = (this.model.groups || []).filter(function (x) { return x.id === gid; })[0];
          html += '<span class="chip">' + esc(g ? g.name : gid)
            + '<button data-delgroup="' + gid + '" title="移出">✕</button></span>';
        }, this);
        html += '</div>';
        html += '<button class="btn btn-sm" id="btnJoinGroup">加入补偿组</button>';
        html += '</div>';
      }

      /* 危险操作 */
      html += '<div class="igroup" style="border-top:1px solid var(--border);padding-top:14px">'
        + '<button class="btn btn-danger" id="btnDelCell" style="width:100%">删除该元素</button></div>';

      this.propBody.innerHTML = html;
      this.bindProps(cell);
    },

    /**
     * 算子重新绑定下拉框。
     * 按算子分组罗列全部可用算子，当前绑定的项标 selected；
     * 若原算子已删除，额外给一个提示用的占位项。
     */
    rebindSelect: function (cell) {
      var cur = (cell.data && cell.data.opId) || '';
      var all = Store.listOperators();
      var groups = [
        { key: 'http', label: 'HTTP 算子' },
        { key: 'sql', label: 'SQL 算子' },
        { key: 'shell', label: 'Shell 算子' },
        { key: 'inner', label: '内部算子' },
        { key: 'compensate', label: '补偿算子' }
      ];
      var html = '<select data-rebind="1"><option value="">— 选择算子 —</option>';
      if (cur && !Store.getOperator(cur)) {
        html += '<option value="' + esc(cur) + '" selected>（已删除）' + esc(cur) + '</option>';
      }
      groups.forEach(function (g) {
        var list = all.filter(function (o) {
          var meta = Store.OP_TYPES[o.opType] || {};
          return (o.group || meta.group || o.opType) === g.key;
        });
        if (!list.length) return;
        html += '<optgroup label="' + esc(g.label) + '">';
        list.forEach(function (o) {
          html += '<option value="' + esc(o.id) + '"'
            + (o.id === cur ? ' selected' : '') + '>'
            + esc(o.name) + '（' + esc(o.opType) + '）</option>';
        });
        html += '</optgroup>';
      });
      return html + '</select>';
    },

    /** 重新绑定算子：写回 data 与节点名称，并同步画布 */
    onRebind: function (cell, opId) {
      var op = Store.getOperator(opId);
      cell.data = cell.data || {};
      cell.data.opId = opId;
      cell.data.opType = op ? op.opType : (cell.data.opType || 'http');
      if (op) cell.name = op.name;
      this.persist();
      this.render();
      this.select(cell.id);
      UI.toast(op ? ('已绑定算子「' + op.name + '」') : '已解除算子绑定', 'ok');
    },

    renderField: function (cell, f) {
      var val = getPath(cell.data || {}, f.key);
      var tip = f.hint ? '<div class="tip">' + esc(f.hint) + '</div>' : '';
      var label = '<label>' + esc(f.label) + '</label>';

      switch (f.type) {
        case 'textarea':
          return '<div class="ifield">' + label
            + '<textarea rows="4" data-bind="' + f.key + '">' + esc(toStr(val)) + '</textarea>' + tip + '</div>';
        case 'number':
          return '<div class="ifield">' + label
            + '<input type="number" data-bind="' + f.key + '" value="' + esc(toStr(val)) + '">' + tip + '</div>';
        case 'select':
          return '<div class="ifield">' + label
            + '<select data-bind="' + f.key + '">'
            + (f.options || []).map(function (o) {
              return '<option' + (toStr(val) === o ? ' selected' : '') + '>' + esc(o) + '</option>';
            }).join('') + '</select>' + tip + '</div>';
        case 'json':
          return '<div class="ifield">' + label
            + '<textarea rows="5" data-bind="' + f.key + '" data-json="1">'
            + esc(val === undefined || val === null ? '' : JSON.stringify(val, null, 2)) + '</textarea>' + tip + '</div>';
        case 'strlist':
          return '<div class="ifield">' + label
            + '<textarea rows="3" data-bind="' + f.key + '" data-strlist="1">'
            + esc(Array.isArray(val) ? val.join('\n') : toStr(val)) + '</textarea>'
            + tip + '<div class="tip">每行一个</div></div>';
        case 'kv':
        case 'kvpath':
          return '<div class="ifield">' + label + this.kvEditor(f.key, val, f.type === 'kvpath')
            + '<button class="btn btn-sm" data-kvadd="' + f.key + '">＋ 添加</button>' + tip + '</div>';
        case 'rules':
          return '<div class="ifield">' + label
            + '<textarea rows="7" data-bind="' + f.key + '" data-json="1">'
            + esc(val === undefined || val === null ? '' : JSON.stringify(val, null, 2)) + '</textarea>'
            + tip + '</div>';
        default:
          return '<div class="ifield">' + label
            + '<input type="text" data-bind="' + f.key + '" value="' + esc(toStr(val)) + '">' + tip + '</div>';
      }
    },

    kvEditor: function (key, val, withJsonPath) {
      var list = Array.isArray(val) ? val : [];
      if (!list.length) return '<div class="kv-list" data-kvlist="' + key + '"><div class="tip">暂无，点击下方添加</div></div>';
      var rows = list.map(function (item, idx) {
        var k = item.key !== undefined ? item.key : (item.name || '');
        var v = item.value !== undefined ? item.value
          : (item.jsonpathMapping !== undefined ? item.jsonpathMapping : (item.default || ''));
        return '<div class="kv-row">'
          + '<input data-kvkey="' + key + '" data-idx="' + idx + '" placeholder="键" value="' + esc(k) + '">'
          + '<input data-kvval="' + key + '" data-idx="' + idx + '" placeholder="'
          + (withJsonPath ? '取值表达式' : '值') + '" value="' + esc(v) + '">'
          + '<button class="kv-del" data-kvdel="' + key + '" data-idx="' + idx + '" title="删除">✕</button></div>';
      }).join('');
      return '<div class="kv-list" data-kvlist="' + key + '">' + rows + '</div>';
    },

    bindProps: function (cell) {
      var self = this;
      var body = this.propBody;

      var nameInput = body.querySelector('[data-bind="name"]');
      if (nameInput) {
        nameInput.addEventListener('input', function () {
          cell.name = this.value;
          self.persist();
          self.renderNodes();
          self.propTitle.textContent = this.value || NodeDefs.get(cell.cellType).name;
          /* 名称长度会改变节点宽度，需重新测量并重画连线 */
          self.clearSizeCache(cell.id);
          self.renderEdges();
        });
      }

      var typeSel = body.querySelector('[data-bind="cellType"]');
      if (typeSel) {
        typeSel.addEventListener('change', function () {
          var newType = this.value;
          cell.cellType = newType;
          cell.name = NodeDefs.get(newType).name;
          cell.data = NodeDefs.get(newType).defaultData();
          self.persist();
          self.render();
          self.renderProps();
          UI.toast('已切换为「' + NodeDefs.get(newType).name + '」', 'ok');
        });
      }

      /* 算子节点：重新绑定算子 */
      var rebind = body.querySelector('[data-rebind]');
      if (rebind) {
        rebind.addEventListener('change', function () {
          self.onRebind(cell, this.value);
        });
      }

      body.querySelectorAll('[data-bind]').forEach(function (el) {
        var key = el.dataset.bind;
        if (key === 'name' || key === 'cellType') return;
        var evt = (el.tagName === 'SELECT') ? 'change' : 'input';
        el.addEventListener(evt, function () {
          var v;
          if (el.dataset.json) {
            var parsed = SchemaUtil.tryParse(el.value);
            if (el.value.trim() && parsed === null) {
              UI.toast('JSON 格式有误，未保存该字段', 'err');
              return;
            }
            v = parsed === null ? (el.value.trim() ? el.value : '') : parsed;
          } else if (el.dataset.strlist) {
            v = el.value.split('\n').map(function (s) { return s.trim(); }).filter(Boolean);
          } else if (el.type === 'number') {
            v = el.value === '' ? '' : Number(el.value);
          } else {
            v = el.value;
          }
          self.updateData(cell.id, key, v);
        });
      });

      this.bindKvEditors(cell);

      var joinBtn = body.querySelector('#btnJoinGroup');
      if (joinBtn) joinBtn.addEventListener('click', function () { self.joinGroup(cell); });

      body.querySelectorAll('[data-delgroup]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var gid = btn.dataset.delgroup;
          cell.groupIds = (cell.groupIds || []).filter(function (x) { return x !== gid; });
          self.persist();
          self.renderNodes();
          self.renderProps();
        });
      });

      var delBtn = body.querySelector('#btnDelCell');
      if (delBtn) delBtn.addEventListener('click', function () { self.removeCell(cell.id); });
    },

    bindKvEditors: function (cell) {
      var self = this;
      var body = this.propBody;

      body.querySelectorAll('[data-kvkey],[data-kvval]').forEach(function (el) {
        el.addEventListener('input', function () {
          var key = el.dataset.kvkey || el.dataset.kvval;
          var idx = Number(el.dataset.idx);
          var isKey = el.dataset.kvkey !== undefined;
          var list = getPath(cell.data || {}, key);
          if (!Array.isArray(list)) return;
          var item = list[idx] || {};
          if (isKey) { item.key = el.value; item.name = el.value; }
          else { item.value = el.value; item.jsonpathMapping = el.value; }
          list[idx] = item;
          self.updateData(cell.id, key, list);
        });
      });

      body.querySelectorAll('[data-kvdel]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var key = btn.dataset.kvdel;
          var idx = Number(btn.dataset.idx);
          var list = getPath(cell.data || {}, key) || [];
          list.splice(idx, 1);
          self.updateData(cell.id, key, list);
          self.renderProps();
        });
      });

      body.querySelectorAll('[data-kvadd]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var key = btn.dataset.kvadd;
          var list = getPath(cell.data || {}, key);
          if (!Array.isArray(list)) list = [];
          list.push({ key: '', name: '', value: '', jsonpathMapping: '' });
          self.updateData(cell.id, key, list);
          self.renderProps();
        });
      });
    },

    joinGroup: function (cell) {
      var self = this;
      var groups = this.model.groups || (this.model.groups = []);
      var options = groups.map(function (g) { return { label: g.name + '（' + g.id + '）', value: g.id }; });
      options.push({ label: '＋ 新建补偿组', value: '__new__' });

      UI.promptSelect('选择要加入的补偿组', options, function (val) {
        if (!val) return;
        var gid = val;
        if (val === '__new__') {
          gid = Store.uid();
          groups.push({ id: gid, name: '补偿组_' + (groups.length + 1), type: 'group_compensate', nodes: [] });
        }
        cell.groupIds = cell.groupIds || [];
        if (cell.groupIds.indexOf(gid) < 0) cell.groupIds.push(gid);
        self.persist();
        self.renderNodes();
        self.renderProps();
        UI.toast('已加入补偿组', 'ok');
      });
    },

    /* ---------- 导入导出 ---------- */
    exportDSL: function () {
      var dsl = Store.toDSL(this.model);
      return { dsl: dsl, validation: DslValidator.validate(dsl) };
    },

    importDSL: function (json) {
      var dsl = SchemaUtil.tryParse(json);
      if (!dsl || !Array.isArray(dsl.cells)) throw new Error('不是合法的 DSL：缺少 cells 数组');
      var model = Store.fromDSL(dsl, document.getElementById('planIdInput').value.trim() || 'PLAN_2026_001');
      this.setModel(model);
      this.persist();
      this.fit();
      return model;
    }
  };

  /* ---------- 小工具 ---------- */
  function field(label, control) {
    return '<div class="ifield"><label>' + esc(label) + '</label>' + control + '</div>';
  }
  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function trunc(s, n) { s = String(s); return s.length > n ? s.slice(0, n) + '…' : s; }
  function toStr(v) { return v === null || v === undefined ? '' : String(v); }
  /** 数据库/环境名的短显示（去掉括号说明部分） */
  function shortDb(v) {
    if (!v) return '未指定';
    var s = String(v);
    var i = s.indexOf('（');
    return i > 0 ? s.slice(0, i) : s;
  }
  function getPath(obj, path) {
    if (!path) return undefined;
    return path.split('.').reduce(function (o, k) {
      return (o === null || o === undefined) ? undefined : o[k];
    }, obj);
  }

  global.Designer = Designer;
  global.getPath = getPath;
})(window);
