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
      this.inspector = document.getElementById('inspector');
      this.inspRail = document.getElementById('inspRail');

      /* 注意：DOM 引用必须在 bindXXX 之前全部就绪，
         否则 bindCanvas 内部读不到元素会抛异常，导致后续初始化被中断。 */
      this.bindCatalog();
      this.bindEdgePicker();
      this.bindCanvas();
      this.bindToolbar();
      this.bindKeyboard();
      this.bindInspector();

      // 默认折叠「连线」与「补偿算子」，聚焦常用节点
      this._collapsed.edge = true;
      this._collapsed.compensate = true;
      this.renderCatalog();

      // 编排内容由 App.openDesigner() 注入，这里只初始化空模型
      this.model = { cells: [], groups: [] };
    },

    /* ---------- 模型读写 ---------- */
    /** 加载某实例的编排（实例是编排的主体） */
    loadInstance: function (inst) {
      this.instId = inst.id;
      this.model = {
        cells: JSON.parse(JSON.stringify(inst.cells || [])),
        groups: JSON.parse(JSON.stringify(inst.groups || []))
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
          /* 三种拖拽载荷：
             tpl:<opType> 外部算子模板（拖入即建临时算子）
             op:<id>      已注册算子
             cellType     内置节点
             内置节点用 cellType */
          var payload;
          if (n.isTemplate) payload = 'tpl:' + n.opType;
          else if (n.opId) payload = 'op:' + n.opId;
          else payload = n.cellType;

          var code = n.isTemplate ? (n.opType + ' · 新建')
            : (n.opId ? (n.opType + ' · ' + n.opId.slice(0, 6)) : n.cellType);
          /* 作用域标记：内置 / 复用 / 临时（仅当前实例）。
             临时算子额外给一个「注册」按钮，注册后跨实例可见。 */
          var scopeTag = '';
          var regBtn = '';
          if (n.isTemplate) {
            scopeTag = '<span class="ptag tag-tpl">模板</span>';
          } else if (n.opId) {
            if (n.scope === 'builtin') {
              scopeTag = '<span class="ptag tag-builtin">内置</span>';
            } else if (n.scope === 'local') {
              scopeTag = '<span class="ptag tag-local">临时</span>';
              regBtn = '<button class="preg" data-register="' + esc(n.opId)
                + '" title="注册为复用算子（注册后其他实例也能看到）">注册</button>';
            } else {
              scopeTag = '<span class="ptag tag-shared">复用</span>';
            }
          }
          html += '<div class="pitem' + (n.isTemplate ? ' pitem-tpl' : '') + '"'
            + ' draggable="true" data-payload="' + esc(payload) + '"'
            + ' data-builtin="' + (n.builtin ? 1 : 0) + '"'
            + ' data-scope="' + esc(n.scope || '') + '"'
            + ' title="' + esc(n.desc) + '">'
            + '<span class="pitem-ico" style="background:' + g.meta.color + '">' + esc(n.icon) + '</span>'
            + '<span class="pitem-text">'
            + '<span class="pitem-name">' + esc(n.name) + scopeTag + '</span>'
            + '<span class="pitem-code">' + esc(code) + '</span>'
            + '</span>' + regBtn + '</div>';
        });
        html += '</div></div>';
      });

      if (!totalHit) {
        html = '<div class="empty-hint" style="padding:24px 12px">未找到匹配节点</div>';
      }
      box.innerHTML = html;
    },

    /* ---------- 连线类型选择器 ----------
     * 画布下方的线型选择。选定后，从算子节点桩点拖出的连线
     * 直接使用该类型，无需再进属性面板切换。
     */
    EDGE_TYPES: [
      { cellType: 'edge_common', label: '普通线', dash: 'common', tip: '顺序流转' },
      { cellType: 'edge_decision', label: '决策线', dash: 'decision', tip: '条件成立才流转' },
      { cellType: 'edge_loop', label: '循环线', dash: 'loop', tip: '圈定循环区间' },
      { cellType: 'edge_compensate', label: '补偿线', dash: 'compensate', tip: '失败时转入补偿分支' }
    ],

    /** 当前选中的线型（默认普通线） */
    getEdgeType: function () {
      return this._edgeType || 'edge_common';
    },

    setEdgeType: function (cellType) {
      var valid = this.EDGE_TYPES.some(function (t) { return t.cellType === cellType; });
      if (!valid) return;
      this._edgeType = cellType;
      try { localStorage.setItem('se_edge_type', cellType); } catch (e) { }
      this.renderEdgePicker();
    },

    renderEdgePicker: function () {
      var box = document.getElementById('edgeTypeList');
      if (!box) return;
      var cur = this.getEdgeType();
      var curLabel = '';
      box.innerHTML = this.EDGE_TYPES.map(function (t) {
        if (t.cellType === cur) curLabel = t.label;
        return '<button class="ep-item' + (t.cellType === cur ? ' active' : '') + '"'
          + ' data-edgetype="' + t.cellType + '" title="' + esc(t.tip) + '">'
          + '<span class="ep-dash ' + t.dash + '"></span>'
          + '<span class="ep-label">' + esc(t.label) + '</span>'
          + '</button>';
      }).join('');
      var curEl = document.getElementById('epCur');
      if (curEl) curEl.textContent = curLabel || '普通线';
    },

    bindEdgePicker: function () {
      var self = this;
      var box = document.getElementById('edgeTypeList');
      if (!box) return;
      /* 恢复上次选择 */
      try {
        var saved = localStorage.getItem('se_edge_type');
        if (saved) this._edgeType = saved;
      } catch (e) { }
      this.renderEdgePicker();
      box.addEventListener('click', function (e) {
        var btn = e.target.closest('.ep-item');
        if (!btn) return;
        self.setEdgeType(btn.dataset.edgetype);
        var t = self.EDGE_TYPES.filter(function (x) { return x.cellType === btn.dataset.edgetype; })[0];
        UI.toast('连线类型已切换为「' + (t ? t.label : '') + '」', 'ok');
      });
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

      // 节点库内的「注册」按钮：把临时算子注册为复用算子
      document.getElementById('nodeCatalog').addEventListener('click', function (e) {
        var btn = e.target.closest && e.target.closest('[data-register]');
        if (!btn) return;
        e.stopPropagation();
        e.preventDefault();
        var opId = btn.dataset.register;
        var op = Store.getOperator(opId);
        if (!op) { UI.toast('算子不存在', 'err'); return; }

        UI.confirmAction(
          '将「' + op.name + '」注册为复用算子？\n\n注册后所有实例都能看到并使用它。',
          function () {
            Api.operator.register([opId]).then(function (res) {
              if (!res.ok) { UI.toast(res.msg || '注册失败', 'warn'); return; }
              self.renderCatalog(search.value);
              UI.toast('「' + op.name + '」已注册为复用算子', 'ok');
            });
          }
        );
      });

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

        /* 调色板项可能是三种载荷：
           op:<算子id>  已注册算子
           tpl:<opType> 外部算子模板（拖入即建临时算子）
           其他          内置 cellType */
        var cellType, opId = '';
        if (raw.indexOf('op:') === 0) {
          cellType = NodeDefs.OP_CELLTYPE;
          opId = raw.slice(3);
        } else if (raw.indexOf('tpl:') === 0) {
          cellType = NodeDefs.OP_CELLTYPE;
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

        /* 模板：先在本实例创建临时算子，再绑定到新节点 */
        if (raw.indexOf('tpl:') === 0) {
          var opType = raw.slice(4);
          opId = self.createTempOperator(opType);
          if (!opId) return;
        }
        self.addNode(cellType, Math.round(pt.x - 80), Math.round(pt.y - 32), opId);
      });

      this.canvas.addEventListener('mousedown', function (e) {
        /* 点在节点或连线上：交给各自的处理器 */
        if (e.target.closest && (e.target.closest('.node') || e.target.closest('.edge-hit'))) return;

        /* 中键 / 右键：直接进入平移 */
        if (e.button === 1 || e.button === 2) {
          self._panning = { sx: e.clientX, sy: e.clientY, ox: self.pan.x, oy: self.pan.y, moved: false };
          self.canvas.classList.add('panning');
          e.preventDefault();
          return;
        }

        /* 左键按下空白处：先记为「待定平移」，
           若鼠标移动则变成拖拽画布，若原地松开则视为点空白（清选中+收面板）。
           这样既能自由拖动整个画布，又不丢失点击空白的语义。 */
        self._pendingPan = {
          sx: e.clientX, sy: e.clientY,
          ox: self.pan.x, oy: self.pan.y,
          moved: false, button: e.button
        };
      });

      window.addEventListener('mousemove', function (e) {
        /* 左键待定平移：超过阈值才转为真正的拖拽，避免轻微抖动被当成平移 */
        if (self._pendingPan && !self._panning) {
          var dx = e.clientX - self._pendingPan.sx;
          var dy = e.clientY - self._pendingPan.sy;
          if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
            self._panning = self._pendingPan;
            self._pendingPan = null;
            self.canvas.classList.add('panning');
          }
        }
        if (self._panning) {
          var mx = e.clientX - self._panning.sx;
          var my = e.clientY - self._panning.sy;
          self.pan.x = self._panning.ox + mx;
          self.pan.y = self._panning.oy + my;
          self._panning.moved = true;
          self.applyTransform();
          return;
        }
        if (self._drag) self.onNodeDrag(e);
        if (self._linking) self.onLinking(e);
      });

      window.addEventListener('mouseup', function (e) {
        /* 左键在空白处按下但没拖动 → 视为点击空白：清选中并收起面板 */
        if (self._pendingPan) {
          self._pendingPan = null;
          self.select(null);
          self.collapseInspector();
          return;
        }
        if (self._panning) {
          self._panning = null;
          self.canvas.classList.remove('panning');
          return;
        }
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
      window.addEventListener('blur', function () {
        self.cancelLink();
        self._pendingPan = null;
        self._panning = null;
        self.canvas.classList.remove('panning');
      });
      document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' || e.keyCode === 27) { self.cancelLink(); self.hideCtxMenu(); }
      });

      /* 右键：命中节点/连线则弹出上下文菜单，否则仅屏蔽默认菜单 */
      this.canvas.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        var hit = self.hitTest(e.target);
        if (!hit) { self.hideCtxMenu(); return; }
        /* 右键是明确的编辑意图，直接展开面板 */
        self.select(hit.id, { openPanel: true });
        self.showCtxMenu(e.clientX, e.clientY, hit);
      });

      /* 双击节点：展开属性面板并聚焦名称输入框 */
      this.nodesLayer.addEventListener('dblclick', function (e) {
        var nodeEl = e.target.closest && e.target.closest('.node');
        if (!nodeEl) return;
        e.preventDefault();
        self.select(nodeEl.dataset.id, { openPanel: true });
        self.focusPropEditor();
      });

      /* 双击连线：展开属性面板并聚焦其属性编辑 */
      this.edgesLayer.addEventListener('dblclick', function (e) {
        var hit = self.hitTest(e.target);
        if (!hit || hit.kind !== 'edge') return;
        e.preventDefault();
        self.select(hit.id, { openPanel: true });
        self.focusPropEditor();
      });

      /* 点击任意处关闭右键菜单 */
      document.addEventListener('mousedown', function (e) {
        if (!e.target.closest || !e.target.closest('#ctxMenu')) self.hideCtxMenu();
      });
      window.addEventListener('blur', function () { self.hideCtxMenu(); });
      this.canvas.addEventListener('wheel', function () { self.hideCtxMenu(); }, { passive: true });

      /* 滚轮平移画布：像列表一样上下翻动，而不是缩放。
         - 普通滚轮：垂直平移
         - Shift + 滚轮：水平平移（触控板横向滑动的常见映射）
         缩放统一交给工具栏的 + / − / 适应 按钮，避免误触。 */
      this.canvas.addEventListener('wheel', function (e) {
        e.preventDefault();
        /* deltaMode: 0=像素 1=行 2=页；行/页模式换算成像素，让手感一致 */
        var unit = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? self.canvas.clientHeight : 1);
        var dx = e.deltaX * unit;
        var dy = e.deltaY * unit;

        if (e.shiftKey) {
          /* Shift：把垂直滚动量转成水平平移 */
          self.pan.x -= (dy || dx);
        } else {
          self.pan.x -= dx;
          self.pan.y -= dy;
        }
        self.applyTransform();
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
          /* 画布已重置，之前选中的元素必然不存在，收起面板 */
          self.collapseInspector();
          self.updateStatus();
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
        if (e.key === 'Escape') {
          self.cancelLink();
          self.hideCtxMenu();
          /* Esc：面板开着就收起（移动端/小屏友好），否则清空选中 */
          if (!self.isInspectorCollapsed()) self.collapseInspector();
          else self.select(null);
        }
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
      /* 拖入新节点是明确的编辑意图：选中并展开属性面板 */
      this.select(id, { openPanel: true });
      return cell;
    },

    /**
     * 轻微避让：仅当新节点与已有节点真正压住时，才就近挪开一点点。
     *
     * 设计原则：用户是「明确拖到某个位置」的，必须尊重目标点。
     * 因此：
     *   - 只有矩形真正相交（不留额外间距）才算冲突；
     *   - 候选位置先按吸附网格取整再判定，避免「算着不冲突、吸附后又压上」的假空位；
     *   - 阶梯探测位移量小的优先；
     *   - 位移上限很小（约两个节点宽），避免把节点甩到画布最底部；
     *   - 全部被占时按固定错位阶梯顺延，保证连续拖入同一点不会层层重叠。
     */
    avoidOverlap: function (cell) {
      var self = this;
      var others = this.getNodes().filter(function (n) { return n.id !== cell.id; });

      var GRID = 10;                    // snapNode 的吸附网格
      /* 兜底尺寸：与画布实测值一致（168×76 有徽标 / 168×52 无徽标）。
         注意 52 是「单纯一句话描述」的高度，节点有副标题时更高，
         因此判定时额外留 8px 余量，宁可多让开一点也不要压住。 */
      var DEF_W = 168, DEF_H_BADGE = 84, DEF_H_PLAIN = 60;

      /**
       * 取节点尺寸。
       * 刚拖入的节点 DOM 尚未渲染，nodeSize 会返回 provisional 估值；
       * 此时按「是否有徽标」给出更贴近实际的估值 —— 补偿算子没有徽标所以更矮。
       */
      function sizeOf(n) {
        var s = self.nodeSize(n.id);
        if (s && !s.provisional && s.h > 0) return { w: s.w, h: s.h };
        var ps = self.nodePresentation(n);
        return { w: DEF_W, h: (ps && ps.badges) ? DEF_H_BADGE : DEF_H_PLAIN };
      }

      var mySize = sizeOf(cell);
      /* 与 snapNode 保持一致的取整，判定前先对齐，避免假空位 */
      function snap(v) { return Math.round(v / GRID) * GRID; }

      if (!others.length) return;

      function hits(ax, ay) {
        return others.some(function (o) {
          var os = sizeOf(o);
          return !(ax + mySize.w <= o.x || o.x + os.w <= ax ||
                   ay + mySize.h <= o.y || o.y + os.h <= ay);
        });
      }

      var ox = snap(cell.x), oy = snap(cell.y);
      if (!hits(ox, oy)) { cell.x = ox; cell.y = oy; return; }   // 目标位置空着，尊重用户选择

      /* 以目标点为原点做「环状扩张」搜索：
         第 r 环覆盖半径 r*STEP 的整圈格子，按位移量从小到大遍历。
         扩展半径随已有节点数自适应，保证连续拖入同一坐标时
         总能找到空位（而不是退回原位造成重叠）。 */
      var STEP_X = 30, STEP_Y = 24;
      var maxRing = Math.max(8, Math.ceil(Math.sqrt(others.length + 1)) * 2 + 4);
      var found = false;

      for (var r = 1; r <= maxRing && !found; r++) {
        var ringPts = [];
        for (var gx = -r; gx <= r; gx++) {
          for (var gy = -r; gy <= r; gy++) {
            /* 只取当前环的边框 */
            if (Math.max(Math.abs(gx), Math.abs(gy)) !== r) continue;
            ringPts.push({ x: snap(ox + gx * STEP_X), y: snap(oy + gy * STEP_Y) });
          }
        }
        /* 同环内也按位移量排序，让它先试最近的位置 */
        ringPts.sort(function (a, b) {
          return (Math.abs(a.x - ox) + Math.abs(a.y - oy)) - (Math.abs(b.x - ox) + Math.abs(b.y - oy));
        });
        for (var i = 0; i < ringPts.length; i++) {
          if (!hits(ringPts[i].x, ringPts[i].y)) {
            cell.x = ringPts[i].x;
            cell.y = ringPts[i].y;
            found = true;
            break;
          }
        }
      }

      /* 极端情况（画布被铺满）：保留在目标点，由用户手动拖开 */
      if (!found) { cell.x = ox; cell.y = oy; }
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

    /**
     * 是否为强制节点（开始 / 结束）。
     * 后端 DslParser 用 Optional.get() 提取首尾节点（缺失会抛
     * NoSuchElementException），因此这两个节点不可删除；连线无法独立表达
     * 流程边界，宁可整体重建也不允许删掉它们。
     */
    isMandatory: function (cell) {
      if (!cell) return false;
      return cell.cellType === 'node_start' || cell.cellType === 'node_end';
    },

    /**
     * 节点名是否允许修改。
     * 仅两类可改：
     *   1) 外部算子节点（http / sql / shell）
     *   2) 补偿算子节点
     * 开始/结束节点与内置算子节点的名称由类型定义决定，不可改名。
     */
    isNameEditable: function (cell) {
      if (!cell) return false;
      if (this.isMandatory(cell)) return false;
      if (!NodeDefs.isOperatorNode(cell.cellType)) return false;
      var op = cell.data && cell.data.opId ? Store.getOperator(cell.data.opId) : null;
      if (!op) return false;
      if (Store.isBuiltinOperator(op)) return false;
      /* 外部算子：名称在「算子配置」里编辑，面板不再单列一项，但依然算「可改名」 */
      return true;
    },

    /**
     * 外部算子（http / sql / shell）的可编辑配置表单。
     * 与「算子注册」页保持一致，编辑直接写回算子对象本身。
     * 输入项统一用 data-opfield 标记，由 bindOuterOpForm() 绑定。
     */
    renderOuterOpForm: function (op) {
      var html = '<div class="igroup">';
      html += '<div class="igroup-head"><span class="g-dot"></span>算子配置</div>';

      /* 补偿算子是同族外部算子的副本，字段结构完全一致，
         因此统一归一化到基础类型后再分支渲染。 */
      var t = Store.baseOpType(op.opType);

      if (t === 'http') {
        /* 字段严格对齐后端 node_outer_http：
           protocol / url / requestType / outputs / inputs / errorno / rollbackRule */
        html += field('算子名称', '<input type="text" data-opfield="name" value="' + esc(op.name || '') + '">');
        html += '<div class="ifield"><label>协议 / 请求方法</label>'
          + '<div class="grid-2">'
          + '<select data-opfield="protocol">'
          + ['http', 'restful'].map(function (p) {
            return '<option' + (op.protocol === p ? ' selected' : '') + '>' + p + '</option>';
          }).join('')
          + '</select>'
          + '<select data-opfield="requestType">'
          + ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].map(function (m) {
            return '<option' + (op.requestType === m ? ' selected' : '') + '>' + m + '</option>';
          }).join('')
          + '</select></div></div>';
        html += field('请求地址', '<input type="text" data-opfield="url" value="' + esc(op.url || '')
          + '" placeholder="http://api.example.com/user/{userId}">');

        /* 入参：与数据映射同构（reqPath/reqQuery/reqHeaders/reqBodyForm） */
        html += this.renderOpRuleTable('inputs.reqHeaders', 'Header 入参', op);
        html += this.renderOpRuleTable('inputs.reqPath', 'Path 入参', op);
        html += this.renderOpRuleTable('inputs.reqQuery', 'Query 入参', op);
        html += this.renderOpRuleTable('inputs.reqBodyForm', '表单入参', op);
        html += '<div class="ifield"><label>请求体类型</label>'
          + '<select data-opfield="inputs.reqBodyType">'
          + ['json', 'form'].map(function (t) {
            return '<option' + (op.inputs && op.inputs.reqBodyType === t ? ' selected' : '') + '>'
              + t + '</option>';
          }).join('')
          + '</select></div>';
        html += '<div class="ifield"><label>请求体 Schema</label>'
          + '<textarea rows="6" spellcheck="false" data-opfield="inputs.reqBodyOther"'
          + ' placeholder=\'{"type":"object","properties":{}}\'>'
          + esc((op.inputs && (op.inputs.reqBodyOther || op.inputs.bodyJsonSchema)) || '') + '</textarea></div>';

        html += '<div class="ifield"><label>出参 Schema</label>'
          + '<textarea rows="6" spellcheck="false" data-opfield="outputs.outputsJsonSchema"'
          + ' placeholder=\'{"type":"object","properties":{}}\'>'
          + esc((op.outputs && op.outputs.outputsJsonSchema) || '') + '</textarea></div>';
        html += field('出参内容类型', '<input type="text" data-opfield="outputs.contentType" value="'
          + esc((op.outputs && op.outputs.contentType) || 'application/json') + '">');
        html += field('错误码', '<input type="text" data-opfield="errorno" value="'
          + esc(op.errorno || '') + '" placeholder="如 5000">');

      } else if (t === 'sql') {
        html += field('算子名称', '<input type="text" data-opfield="name" value="' + esc(op.name || '') + '">');
        /* 数据库连接由「环境配置」统一维护并支持连通性测试，
           这里只做引用选择，避免配置散落在每个算子里。 */
        html += Envs.selectHtml('sql', op.envId || '', 'envId');
        html += '<div class="ifield"><label>SQL 语句</label>'
          + '<textarea rows="10" class="code-editor" spellcheck="false" data-opfield="sql"'
          + ' placeholder="SELECT id, name FROM t_user WHERE id = #{userId}">'
          + esc(op.sql || '') + '</textarea>'
          + '<div class="tip">支持 <code>#{参数名}</code> 占位，运行期由编排传入。</div></div>';

      } else if (t === 'shell') {
        html += field('算子名称', '<input type="text" data-opfield="name" value="' + esc(op.name || '') + '">');
        /* 执行环境同上，改为引用环境配置 */
        html += Envs.selectHtml('shell', op.envId || '', 'envId');
        html += '<div class="ifield"><label>超时（毫秒）</label>'
          + '<input type="number" data-opfield="timeout" value="' + esc(op.timeout || 30000) + '">'
          + '</div>';
        html += '<div class="ifield"><label>脚本内容</label>'
          + '<textarea rows="10" class="code-editor" spellcheck="false" data-opfield="script"'
          + ' placeholder="#!/bin/bash&#10;echo &quot;hello ${name}&quot;">'
          + esc(op.script || '') + '</textarea>'
          + '<div class="tip">支持 <code>${参数名}</code> 占位，运行期由编排传入。</div></div>';
      } else {
        /* dubbo / webservice 目前只在后端定义 cellType，前端尚未提供
           注册入口与字段定义。这里给出明确说明，而不是渲染一个空表单。 */
        html += '<div class="tip">该类型（' + esc(op.opType) + '）暂未在前端提供配置项，'
          + '字段结构以后端定义为准。</div>';
      }

      html += '<div class="tip">此处修改的是算子本身，所有引用它的节点都会同步生效。</div>';
      return html + '</div>';
    },

    /**
     * 外部算子的入参规则表（Header / Path / Query / 表单）。
     * 与内置算子的 renderRuleTable 结构一致，但值直接读写算子对象，
     * 而非节点的 data.ruleConfig —— 因为这是算子自身的定义。
     */
    renderOpRuleTable: function (key, label, op) {
      var cols = ['name', 'key', 'type', 'jsonpathMapping', 'defaultValue'];
      var colTypes = { type: ['', 'Text', 'File', 'Number', 'Boolean', 'Array', 'Object'] };
      var rows = getPath(op, key);
      rows = Array.isArray(rows) ? rows.slice() : [];
      if (!rows.length) rows = [{}];      // 默认一行占位

      var html = '<div class="ifield"><label>' + esc(label) + '</label>';
      html += '<div class="param-table" data-opruletable="' + esc(key) + '">';
      html += '<table class="ptable rtable"><thead><tr>'
        + cols.map(function (c) {
          return '<th title="' + esc(c) + '">' + esc(Store.ruleColLabel(c)) + '</th>';
        }).join('')
        + '<th></th></tr></thead><tbody>';
      rows.forEach(function (r, i) {
        html += '<tr data-idx="' + i + '">';
        cols.forEach(function (c) {
          var opts = colTypes[c];
          var v = (r && r[c] !== undefined && r[c] !== null) ? r[c] : '';
          if (opts) {
            html += '<td><select class="rt-cell" data-col="' + esc(c) + '">'
              + opts.map(function (o) {
                return '<option value="' + esc(o) + '"'
                  + (String(v) === o ? ' selected' : '') + '>' + esc(o || '—') + '</option>';
              }).join('')
              + '</select></td>';
          } else {
            html += '<td><input class="rt-cell" data-col="' + esc(c)
              + '" value="' + esc(v) + '"></td>';
          }
        });
        html += '<td><button class="btn btn-sm pt-del" data-opruledel="1" title="删除该行">✕</button></td>';
        html += '</tr>';
      });
      html += '</tbody></table>';
      html += '<button class="btn btn-sm" data-opruleadd="' + esc(key) + '">＋ 添加</button>';
      return html + '</div></div>';
    },

    /**
     * 算子配置里的 key/value 表格（Headers、Query 参数）。
     * 行内输入用 data-opkv 标记，由 bindOuterOpForm() 处理增删改。
     */
    renderKvTable: function (key, label, list, addText) {
      list = Array.isArray(list) ? list : [];
      var html = '<div class="ifield"><label>' + esc(label) + '</label>';
      html += '<div class="param-table" data-opkvtable="' + esc(key) + '">';
      if (!list.length) {
        html += '<div class="tip">暂无，点击下方添加</div>';
      } else {
        html += '<table class="ptable"><thead><tr><th>名称</th><th>值</th><th></th></tr></thead><tbody>';
        list.forEach(function (it, i) {
          html += '<tr data-idx="' + i + '">'
            + '<td><input class="ok-k" value="' + esc(it.key || it.k || '') + '"></td>'
            + '<td><input class="ok-v" value="' + esc(it.value === undefined ? (it.v || '') : it.value) + '"></td>'
            + '<td><button class="btn btn-sm pt-del" data-opkvdel="1" title="删除">✕</button></td>'
            + '</tr>';
        });
        html += '</tbody></table>';
      }
      html += '<button class="btn btn-sm" data-opkvadd="' + esc(key) + '">＋ ' + esc(addText || '添加') + '</button>';
      return html + '</div></div>';
    },

    /**
     * 拖入外部算子模板时，就地创建一个临时算子（scope=local）。
     * 名称自动编号（如「HTTP 接口 2」），随后由用户在属性面板填写配置。
     * @returns {string} 新建算子的 id，失败返回空串
     */
    createTempOperator: function (opType) {
      if (Store.REGISTERABLE_TYPES.indexOf(opType) < 0) return '';
      if (!Store.currentInstanceId()) {
        UI.toast('请先打开一个实例', 'warn');
        return '';
      }
      var meta = Store.OP_TYPES[opType] || {};
      /* 同类型已有 n 个，则命名为「<类型名> n+1」 */
      var n = Store.listOperators().filter(function (o) { return o.opType === opType; }).length;
      var op = Store.newOperator(opType);       // 默认 scope=local
      op.name = (meta.name || opType) + ' ' + (n + 1);
      op.description = '';
      Store.saveOperator(op);
      UI.toast('已新建临时算子「' + op.name + '」，请在右侧填写配置', 'ok');
      return op.id;
    },

    /**
     * 取与指定节点相连的所有连线 id。
     * 删除节点时会连带删掉这些线，需要据此判断「当前选中的元素是否也没了」。
     */
    _linkedNeighborIds: function (nodeId) {
      return this.model.cells.filter(function (c) {
        if (!DslValidator.isEdge(c.cellType)) return false;
        return (c.source && c.source.cell === nodeId) || (c.target && c.target.cell === nodeId);
      }).map(function (c) { return c.id; });
    },

    removeCell: function (id) {
      var self = this;
      var cell = this.getCell(id);
      if (!cell) return;

      /* 强制节点：直接拦截，避免右键菜单 / 删除按钮 / Delete 键绕过 */
      if (this.isMandatory(cell)) {
        UI.toast('开始与结束是强制节点，不能删除', 'warn');
        return;
      }

      var doRemove = function () {
        /* 面板当前是否正显示被删的这个元素 —— 决定删除后是否收起 */
        var wasShowingDeleted = (self.selectedId === id);
        var neighborIds = self._linkedNeighborIds(id);

        self.model.cells = self.model.cells.filter(function (c) { return c.id !== id; });
        self.model.cells = self.model.cells.filter(function (c) {
          if (!DslValidator.isEdge(c.cellType)) return true;
          return !(c.source && c.source.cell === id) && !(c.target && c.target.cell === id);
        });

        /* 一并删掉的相连线里若包含当前选中项，也视作「选中的元素没了」 */
        neighborIds.forEach(function (nid) {
          if (self.selectedId === nid) wasShowingDeleted = true;
        });

        self.persist();
        self.render();

        if (wasShowingDeleted) {
          /* 选中的元素被删除：清空选中并收起属性面板，
             避免面板停留在「未选中任何元素」的空态。 */
          self.selectedId = null;
          self.applySelectionClass();
          self.collapseInspector();
          self.updateStatus();
        } else {
          /* 删的是别的元素：保持当前选中与面板内容不变 */
          self.renderProps();
          self.updateStatus();
        }
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
      /* 开始节点的全局参数数量徽标会随配置变化，需要重绘节点层 */
      if (cell.cellType === 'node_start') {
        this.clearSizeCache();
        this.renderNodes();
        this.renderEdges();
      }
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
        var mandatory = this.isMandatory(cell);
        items.push({ label: '编辑', icon: '✎', act: 'edit' });
        items.push({ label: '重命名', icon: 'T', act: 'rename' });
        items.push({ sep: true });
        /* 强制节点不允许复制（复制会得到第二个开始/结束节点）*/
        if (!mandatory) {
          items.push({ label: '复制节点', icon: '⧉', act: 'duplicate' });
          items.push({ label: '加入补偿组', icon: '⟲', act: 'group' });
        }
        if (!mandatory) {
          items.push({ sep: true });
          items.push({ label: '删除节点', icon: '✕', act: 'delete', danger: true });
        } else {
          items.push({ sep: true });
          items.push({ label: '强制节点不可删除', icon: '🔒', disabled: true });
        }
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
        if (it.disabled) {
          return '<button class="ctx-item" disabled>'
            + '<span class="ctx-ico">' + it.icon + '</span>'
            + '<span>' + esc(it.label) + '</span>'
            + '</button>';
        }
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
          /* 明确要编辑：展开面板并聚焦 */
          this.select(cell.id, { openPanel: true });
          this.focusPropEditor();
          break;
        case 'rename':
          this.select(cell.id);
          UI.prompt({
            title: '重命名',
            value: cell.name || '',
            placeholder: '请输入节点名称',
            required: true,
            requiredMsg: '名称不能为空',
            maxLength: 40,
            onOk: function (val) {
              var v = (val || '').trim();
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

    /** 复制节点（连线不复制）；强制节点不可复制 */
    duplicateNode: function (cell) {
      if (this.isMandatory(cell)) {
        UI.toast('开始与结束是强制节点，不能复制', 'warn');
        return;
      }
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

    /* ---------- 选中 ----------
     * options.openPanel：是否展开属性面板。
     * 单击只做高亮，双击 / 右键 / 新建节点才打开面板，
     * 这样单点一下不会把右侧面板弹出，保持画布视野。
     *
     * 注意：这里刻意不调用 renderNodes()。
     * 若整体重建节点层，mousedown 时的元素实例会被丢弃，
     * 浏览器就无法把 press/release 合成 click 与 dblclick，
     * 导致「双击节点」永远不触发。因此选中态只切换 class。
     */
    select: function (id, options) {
      options = options || {};
      var prev = this.selectedId;
      this.selectedId = id;

      /* 仅切换选中样式，保持 DOM 元素实例稳定 */
      if (prev && prev !== id) {
        var prevEl = this.nodesLayer.querySelector('.node[data-id="' + prev + '"]');
        if (prevEl) prevEl.classList.remove('selected');
        var prevEdge = this.edgesLayer.querySelector('.edge-hit.selected');
        if (prevEdge) prevEdge.classList.remove('selected');
      }
      this.applySelectionClass();

      if (id && options.openPanel) this.expandInspector();
      /* 面板收起时不渲染内容，省一次 DOM 重建 */
      if (!this.isInspectorCollapsed()) this.renderProps();

      this.updateStatus();
    },

    /** 把 selected class 同步到当前选中的节点/连线元素上 */
    applySelectionClass: function () {
      if (!this.nodesLayer) return;
      var id = this.selectedId;
      this.nodesLayer.querySelectorAll('.node.selected').forEach(function (el) {
        if (el.dataset.id !== id) el.classList.remove('selected');
      });
      if (id) {
        var el = this.nodesLayer.querySelector('.node[data-id="' + id + '"]');
        if (el) el.classList.add('selected');
      }
      if (this.edgesLayer) {
        this.edgesLayer.querySelectorAll('.edge-hit').forEach(function (e) {
          e.classList.toggle('selected', e.getAttribute('data-id') === id);
        });
      }
    },

    /* ---------- 属性面板：收起 / 展开 / 调宽 ---------- */

    isInspectorCollapsed: function () {
      return !!this.inspector && this.inspector.classList.contains('collapsed');
    },

    collapseInspector: function () {
      if (!this.inspector) return;
      this.inspector.classList.add('collapsed');
      /* 收起时清掉内联宽度，让 .collapsed 的 0 宽度生效 */
      this.inspector.style.width = '';
      this.inspector.style.flexBasis = '';
      if (this.inspRail) this.inspRail.style.display = '';
      var self = this;
      requestAnimationFrame(function () { self.clearSizeCache(); self.render(); });
    },

    expandInspector: function () {
      if (!this.inspector) return;
      this.inspector.classList.remove('collapsed');
      if (this.inspRail) this.inspRail.style.display = 'none';
      /* 展开时立刻写回记忆宽度，避免读到过渡中间态 */
      var w = this._inspWidth || this.readSavedWidth() || 340;
      this.inspector.style.width = w + 'px';
      this.inspector.style.flexBasis = w + 'px';
      /* 从收起态展开时补渲染一次属性内容 */
      this.renderProps();
      /* 画布尺寸变了，重算连线 */
      var self = this;
      requestAnimationFrame(function () {
        self.clearSizeCache();
        self.render();
      });
    },

    toggleInspector: function () {
      if (this.isInspectorCollapsed()) this.expandInspector();
      else this.collapseInspector();
    },

    /** 读取记忆的面板宽度（无效返回 0） */
    readSavedWidth: function () {
      try {
        var w = Number(localStorage.getItem('se_insp_w')) || 0;
        return (w >= 260) ? w : 0;
      } catch (e) { return 0; }
    },

    persistInspectorWidth: function () {
      try {
        if (this._inspWidth) localStorage.setItem('se_insp_w', String(this._inspWidth));
      } catch (e) { }
    },

    /**
     * 设置属性面板宽度。
     * 夹在 260 ~ 视口 60% 之间；收起状态下只记录值、不写内联样式，
     * 待下次 expandInspector() 时再应用，避免与 .collapsed 的 0 宽度冲突。
     */
    setInspectorWidth: function (px) {
      if (!this.inspector) return this._inspWidth;
      var min = 260;
      var max = Math.max(min, Math.round(window.innerWidth * 0.6));
      px = Math.max(min, Math.min(max, Math.round(px)));
      this._inspWidth = px;
      if (!this.isInspectorCollapsed()) {
        this.inspector.style.width = px + 'px';
        this.inspector.style.flexBasis = px + 'px';
      }
      this.persistInspectorWidth();
      return px;
    },

    restoreInspectorWidth: function () {
      var w = this.readSavedWidth();
      if (w) this.setInspectorWidth(w);
    },

    /** 绑定收起按钮、窄条与拖拽调宽手柄 */
    bindInspector: function () {
      var self = this;
      this.inspector = document.getElementById('inspector');
      this.inspRail = document.getElementById('inspRail');
      var resizer = document.getElementById('inspResizer');
      var btnCollapse = document.getElementById('btnCollapseInsp');

      if (btnCollapse) btnCollapse.addEventListener('click', function () { self.collapseInspector(); });
      if (this.inspRail) this.inspRail.addEventListener('click', function () { self.expandInspector(); });

      this.restoreInspectorWidth();

      if (!resizer) return;
      resizer.addEventListener('mousedown', function (e) {
        e.preventDefault();
        resizer.classList.add('active');
        document.body.classList.add('resizing-insp');

        var onMove = function (ev) {
          /* 面板在右侧，宽度 = 视口右边界 - 鼠标 x */
          self.setInspectorWidth(window.innerWidth - ev.clientX);
        };
        var onUp = function () {
          resizer.classList.remove('active');
          document.body.classList.remove('resizing-insp');
          window.removeEventListener('mousemove', onMove);
          window.removeEventListener('mouseup', onUp);
          /* 宽度变化后重算连线与尺寸缓存 */
          requestAnimationFrame(function () { self.clearSizeCache(); self.render(); });
        };
        window.addEventListener('mousemove', onMove);
        window.addEventListener('mouseup', onUp);
      });
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
          /* 全局参数按后端契约不参与连线，因此不渲染桩点 */
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
             因此不能在 select() 之后对旧元素加 class —— 那会作用到已被丢弃的节点上。
             单击只高亮不展开属性面板（双击才展开），避免拖动时面板来回弹。 */
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
        /* 补偿算子复用同族外部算子的摘要逻辑 */
        var ot = Store.baseOpType(op.opType);
        if (ot === 'http') {
          badges.push('<span class="nbadge">' + esc((op.requestType || op.method || 'GET')) + '</span>');
        } else if (ot === 'sql') {
          badges.push('<span class="nbadge op">' + esc(shortDb(op.database)) + '</span>');
        } else if (ot === 'shell' || op.opType === 'execShell') {
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
      /* 强制节点：标注不可删除 */
      if (n.cellType === 'node_start' || n.cellType === 'node_end') {
        badges.push('<span class="nbadge lock" title="强制节点，不可删除">🔒</span>');
      }
      if (n.groupIds && n.groupIds.length) badges.push('<span class="nbadge comp">补偿组</span>');
      if (n.cellType === 'node_inner_sleep' && n.data && n.data.milliseconds) {
        badges.push('<span class="nbadge">' + n.data.milliseconds + 'ms</span>');
      }

      /* 开始节点：展示全局参数个数 */
      if (n.cellType === 'node_start') {
        var gps = (n.data && n.data.globals) || [];
        if (gps.length) badges.push('<span class="nbadge op">参数 ' + gps.length + '</span>');
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

      /* 使用调色板底部选定的线型 */
      var edgeType = this.getEdgeType();
      this.addEdge(l.sourceId, l.sourcePort, targetId, targetId + '_in', edgeType);
      var t = this.EDGE_TYPES.filter(function (x) { return x.cellType === edgeType; })[0];
      UI.toast('已创建「' + (t ? t.label : '连线') + '」', 'ok');
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

      /* 切换到别的节点时重置规则表格的界面行数，避免行数串到其他节点 */
      if (this._ruleUiRowsFor !== this.selectedId) {
        this._ruleUiRows = {};
        this._ruleUiRowsFor = this.selectedId;
      }

      if (!cell) {
        this.propIcon.textContent = '◈';
        this.propTitle.textContent = '属性面板';
        this.propSub.textContent = '未选中任何元素';
        this.propBody.innerHTML =
          '<div class="insp-empty">'
          + '<div class="ie-ico">⇦</div>'
          + '<p><strong>双击</strong>画布中的<strong>节点</strong>或<strong>连线</strong><br>即可在此编辑其属性</p>'
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

      /* 算子节点的算子信息（用于决定面板呈现方式） */
      var opNode = NodeDefs.isOperatorNode(cell.cellType);
      var boundOp = (opNode && cell.data && cell.data.opId) ? Store.getOperator(cell.data.opId) : null;
      var isBuiltinOp = !!(boundOp && Store.isBuiltinOperator(boundOp));

      /* 基础信息（名称）：
         仅「外部算子」与「补偿算子」的节点名可改，其余一律只读不展示：
         - 开始 / 结束、内置算子：名称由类型定义，不可改；
         - 外部算子（http/sql/shell）：名称在下方「算子配置」里编辑，避免重复。 */
      var isExternalOp = !!(boundOp && !isBuiltinOp && Store.REGISTERABLE_TYPES.indexOf(boundOp.opType) >= 0);
      var canRename = this.isNameEditable(cell);
      if (canRename && !isExternalOp) {
        html += '<div class="igroup">';
        html += '<div class="igroup-head"><span class="g-dot"></span>基础信息</div>';
        html += field('名称', '<input type="text" data-bind="name" value="' + esc(cell.name || '') + '">');
        html += '</div>';
      }

      /* 算子节点：只展示业务配置。
         算子包类型 / 算子名称 / 内置能力 / opId 都属内部标识，不展示也不可改；
         换算子请删除节点后从左侧节点库重新拖入。 */
      if (opNode) {
        var op = boundOp;

        if (!op) {
          /* 引用的算子已被删除：给出明确提示（唯一的异常态） */
          html += '<div class="igroup">';
          html += '<div class="igroup-head"><span class="g-dot"></span>算子配置</div>';
          html += '<div class="tip" style="color:var(--danger)">'
            + '该算子已被删除。请删除此节点后，从左侧节点库重新拖入所需算子。</div>';
          html += '</div>';
        } else {
          /* 呈现方式由「该类型有无字段定义」决定，而不是由 builtin 标志决定。
             原因：补偿算子为允许改名已不标记 builtin，
             但它与同族基础算子共用 schema，仍应按规则配置渲染。
             - opSchema 非空  → 内置族 / 补偿数据映射：渲染规则配置表
             - opSchema 为空  → 外部算子（http / sql / shell）：渲染可编辑表单
               外部算子的 schema 定义在 designer 的 renderOuterOpForm，不在 OP_SCHEMAS。 */
          var bSchema = Store.opSchema(op.opType);
          if (bSchema.length) {
            var bself = this;
            html += '<div class="igroup">';
            html += '<div class="igroup-head"><span class="g-dot"></span>算子规则配置</div>';
            /* 数据映射 / 补偿数据映射：说明职责，避免与 HTTP 算子的接口配置混淆。
               补偿版复用同一基础类型，故用 baseOpType 归一后判断。 */
            if (Store.baseOpType(op.opType) === 'datamap') {
              html += '<div class="tip">把<b>上游节点的输出</b>按规则组装成<b>下游算子的入参</b>：'
                + '「取值表达式」填写 <code>#节点id$jsonpath</code>，取不到时用「默认值」兜底。</div>';
            }
            bSchema.forEach(function (f) {
              html += bself.renderOpField(cell, op, f);
            });
            html += '<div class="tip">规则以 <code>data.ruleConfig</code> 保存在节点上，'
              + '并随 DSL 一起提交。</div>';
            html += '</div>';
          } else {
            /* 外部算子（http / sql / shell）及其补偿版：
               渲染与「算子注册」页一致的可编辑表单，
               修改直接作用于算子本身（算子被所有引用它的节点共享）。 */
            html += this.renderOuterOpForm(op);
          }
        }
      }

      /* 连线端点 */
      if (DslValidator.isEdge(cell.cellType)) {
        html += '<div class="igroup">';
        html += '<div class="igroup-head"><span class="g-dot"></span>连接关系</div>';
        html += field('起点节点', '<input value="' + esc(cell.source ? cell.source.cell : '') + '" readonly>');
        html += field('终点节点', '<input value="' + esc(cell.target ? cell.target.cell : '') + '" readonly>');
        /* 线类型只读展示：切换请用左侧「连线类型」选择器后重新连线 */
        html += field('线类型', '<input value="' + esc(NodeDefs.get(cell.cellType).name) + '" readonly>');
        html += '<div class="tip">切换线型请用左侧「连线类型」选择器重新连线。</div>';
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

      /* 强组合（补偿组）：
         内置算子节点只做规则配置，不参与补偿组，故不展示此项。 */
      if (!isBuiltinOp
        && !DslValidator.isEdge(cell.cellType)
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

      /* 危险操作：强制节点（开始/结束）只提示不可删，其余给删除按钮。
         注意这里删的是「画布上的节点」，与算子本身是否为内置无关。 */
      html += '<div class="igroup" style="border-top:1px solid var(--border);padding-top:14px">';
      if (this.isMandatory(cell)) {
        html += '<div class="tip tip-lock">'
          + '🔒 ' + esc(cell.name || '该节点') + ' 是流程的强制节点，只能编辑属性，不能删除。'
          + '</div>';
      } else {
        html += '<button class="btn btn-danger" id="btnDelCell" style="width:100%">删除该元素</button>';
      }
      html += '</div>';

      this.propBody.innerHTML = html;
      this.bindProps(cell);
    },

    /* 说明：算子绑定不可在面板内切换。
       原先的「重新绑定」下拉已移除 —— 换算子请删除节点后从左侧节点库重新拖入，
       避免误操作把已配好的规则替换掉。 */

    /**
     * 渲染内置算子的配置项。
     * 值存放在 cell.data.ruleConfig.<key>，与算子模板解耦：
     * 模板定义「可配什么」，ruleConfig 保存「当前配成什么」。
     */
    renderOpField: function (cell, op, f) {
      var cfg = (cell.data && cell.data.ruleConfig) || {};
      var val = getPath(cfg, f.key);
      /* 未配置时用算子模板里的默认值做占位 */
      var dft = getPath(op, f.key);
      var tip = f.hint ? '<div class="tip">' + esc(f.hint) + '</div>' : '';
      var label = '<label>' + esc(f.label) + '</label>';
      var wrap = function (inner) {
        return '<div class="ifield">' + label + inner + tip + '</div>';
      };
      var bind = 'data-opbind="' + esc(f.key) + '"';
      var ph = (dft !== undefined && dft !== null && dft !== '' && typeof dft !== 'object')
        ? ' placeholder="默认：' + esc(String(dft)) + '"' : '';

      switch (f.type) {
        case 'number':
          return wrap('<input type="number" ' + bind + ' value="'
            + esc(val === undefined || val === null ? '' : val) + '"' + ph + '>');

        case 'select':
          return wrap('<select ' + bind + '>'
            + (f.options || []).map(function (o) {
              return '<option value="' + esc(o) + '"'
                + (String(val === undefined ? dft : val) === o ? ' selected' : '') + '>'
                + esc(Store.OP_TYPES[o] ? Store.OP_TYPES[o].name : o) + '</option>';
            }).join('')
            + '</select>');

        case 'textarea':
        case 'json':
          return wrap('<textarea rows="' + (f.type === 'json' ? 7 : 4) + '" ' + bind + '>'
            + esc(typeof val === 'object' && val !== null ? JSON.stringify(val, null, 2)
              : (val === undefined || val === null ? '' : val)) + '</textarea>');

        case 'lines':
          /* 数组 ↔ 每行一项 */
          var arr = Array.isArray(val) ? val : (Array.isArray(dft) ? dft : []);
          return wrap('<textarea rows="4" ' + bind + ' data-lines="1">'
            + esc(arr.join('\n')) + '</textarea>');

        case 'rules':
          return wrap(this.renderRuleTable(f, val, dft));

        default:
          return wrap('<input type="text" ' + bind + ' value="'
            + esc(val === undefined || val === null ? '' : val) + '"' + ph + '>');
      }
    },

    /**
     * 规则表格：可增删行，列由 f.cols 决定，f.colTypes 可指定某列为下拉。
     *
     * 渲染行数 = max(已有数据行数, _ruleUiRows[key], 1)：
     *  - 至少有 1 行空行，打开面板即可直接输入，不必先点「添加规则」
     *  - 点「添加规则」只抬高 _ruleUiRows，空行不进数据
     * 空行在单元格 change 时才由 readRows() 过滤后写入，因此不会污染 ruleConfig。
     */
    renderRuleTable: function (f, val, dft) {
      var cols = f.cols || ['key', 'value'];
      /* 必须复制：下面的补行操作会改数组长度，
         若直接引用 val 就会污染 cell.data.ruleConfig（凭空多出空对象）。 */
      var rows = (Array.isArray(val) ? val : (Array.isArray(dft) ? dft : [])).slice();
      var key = f.key;
      var html = '<div class="param-table" data-ruletable="' + esc(key) + '">';

      /* 界面行数：数据行、手动加过的行、以及至少 1 行占位 */
      var uiRows = this._ruleUiRows && this._ruleUiRows[key] ? this._ruleUiRows[key] : 0;
      var total = Math.max(rows.length, uiRows, 1);
      while (rows.length < total) rows.push({});

      html += '<table class="ptable rtable"><thead><tr>'
        + cols.map(function (c) {
          /* 列名用中文，鼠标悬停仍可看到后端字段名 */
          return '<th title="' + esc(c) + '">' + esc(Store.ruleColLabel(c)) + '</th>';
        }).join('')
        + '<th></th></tr></thead><tbody>';
      rows.forEach(function (r, i) {
        html += '<tr data-idx="' + i + '">';
        cols.forEach(function (c) {
          var opts = f.colTypes && f.colTypes[c];
          var v = (r && r[c] !== undefined && r[c] !== null) ? r[c] : '';
          if (opts) {
            html += '<td><select class="rt-cell" data-col="' + esc(c) + '">'
              + opts.map(function (o) {
                return '<option value="' + esc(o) + '"'
                  + (String(v) === o ? ' selected' : '') + '>' + esc(o) + '</option>';
              }).join('')
              + '</select></td>';
          } else {
            html += '<td><input class="rt-cell" data-col="' + esc(c)
              + '" value="' + esc(v) + '"></td>';
          }
        });
        html += '<td><button class="btn btn-sm pt-del" data-rueldel="1" title="删除该行">✕</button></td>';
        html += '</tr>';
      });
      html += '</tbody></table>';

      html += '<button class="btn btn-sm" data-ruleadd="' + esc(key) + '">＋ 添加规则</button>';
      return html + '</div>';
    },

    renderField: function (cell, f) {
      var val = getPath(cell.data || {}, f.key);
      var tip = f.hint ? '<div class="tip">' + esc(f.hint) + '</div>' : '';
      var label = '<label>' + esc(f.label) + '</label>';

      /* 高级项：默认折叠，避免次要参数喧宾夺主。
         仅改变呈现方式，字段本身仍参与 data-bind 绑定与保存。 */
      if (f.advanced) {
        var filled = val !== undefined && val !== null && String(val) !== ''
          && String(val) !== JSON.stringify({ type: 'object', properties: {} });
        var inner = this.renderField(cell, {
          key: f.key, label: f.label, type: f.type, hint: f.hint,
          options: f.options, cols: f.cols, colTypes: f.colTypes
        });
        return '<details class="ifield adv-field"'
          + (this._advOpen && this._advOpen[f.key] ? ' open' : '')
          + ' data-advkey="' + esc(f.key) + '">'
          + '<summary>高级设置 — ' + esc(f.label)
          + (filled ? ' <span class="adv-dot" title="已配置"></span>' : '')
          + '</summary>' + inner + '</details>';
      }

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
          /* 节点 / 连线的规则表：值存放在 cell.data 上（renderRuleTable 内部处理） */
          return '<div class="ifield">' + label + this.renderRuleTable(f, val) + tip + '</div>';
        case 'params':
          return '<div class="ifield">' + label + this.paramsEditor(f.key, val)
            + '<button class="btn btn-sm" data-paramadd="' + f.key + '">＋ 添加参数</button>'
            + tip + '</div>';
        default:
          return '<div class="ifield">' + label
            + '<input type="text" data-bind="' + f.key + '" value="' + esc(toStr(val)) + '">' + tip + '</div>';
      }
    },

    /**
     * 全局参数表格编辑器（挂在开始节点上，对应后端 StartCell.Data.globals）。
     * 四列：显示名 / 引用键 / 类型 / 值 —— 其中 name 是显示名、key 是引用键。
     */
    paramsEditor: function (key, list) {
      list = Array.isArray(list) ? list : [];
      var html = '<div class="param-table" data-paramtable="' + key + '">';
      if (!list.length) {
        html += '<div class="tip">暂无参数</div>';
      } else {
        html += '<table class="ptable"><thead><tr>'
          + '<th>显示名</th><th>引用键</th><th>类型</th><th>值</th><th></th>'
          + '</tr></thead><tbody>';
        list.forEach(function (p, i) {
          html += '<tr data-idx="' + i + '">'
            + '<td><input class="pt-name" value="' + esc(p.name || '') + '" placeholder="类别"></td>'
            + '<td><input class="pt-key" value="' + esc(p.key || '') + '" placeholder="wftype"></td>'
            + '<td><select class="pt-type">'
            + Store.PARAM_TYPES.map(function (t) {
              return '<option' + (p.type === t ? ' selected' : '') + '>' + t + '</option>';
            }).join('')
            + '</select></td>'
            + '<td><input class="pt-val" value="'
            + esc(p.value === undefined || p.value === null ? '' : p.value) + '"></td>'
            + '<td><button class="btn btn-sm pt-del" title="删除">✕</button></td>'
            + '</tr>';
        });
        html += '</tbody></table>';
      }
      /* 引用写法提示：用 key */
      var keys = list.map(function (p) { return p.key; }).filter(Boolean);
      if (keys.length) {
        html += '<div class="tip">引用写法：'
          + keys.map(function (k) {
            return '<code>' + esc(Store.dynamicRef(k)) + '</code>';
          }).join('　') + '</div>';
      }
      return html + '</div>';
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

      /* 类型不可切换：cellType 是节点身份标识，随意变更会破坏连线引用与
         后端解析（DslParser 按 cellType 路由执行器）。如需换类型请删除后重建。 */

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

      /* 高级设置折叠项：记住展开状态，避免每次重渲染都收起 */
      this._advOpen = this._advOpen || {};
      body.querySelectorAll('details[data-advkey]').forEach(function (d) {
        var k = d.dataset.advkey;
        d.addEventListener('toggle', function () { self._advOpen[k] = d.open; });
      });

      this.bindKvEditors(cell);
      this.bindParamEditors(cell);
      this.bindOpFields(cell);
      this.bindOuterOpForm(cell);

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

    /** 全局参数表格的编辑、增删 */
    bindParamEditors: function (cell) {
      var self = this;

      /** 把表格当前值写回 data（保留本地 id，供 selectId / idxSelectId 绑定） */
      function commit(key) {
        var table = self.propBody.querySelector('[data-paramtable="' + key + '"]');
        if (!table) return;
        var rows = table.querySelectorAll('tbody tr');
        if (!rows.length) return;   /* 空表格不动数据，避免误清空 */

        var next = [];
        rows.forEach(function (tr) {
          var idx = Number(tr.dataset.idx);
          var old = (getPath(cell.data || {}, key) || [])[idx] || {};
          var nameEl = tr.querySelector('.pt-name');
          var keyEl = tr.querySelector('.pt-key');
          var typeEl = tr.querySelector('.pt-type');
          var valEl = tr.querySelector('.pt-val');
          next.push(Object.assign({}, old, {
            id: old.id || Store.uid(),
            name: nameEl ? nameEl.value.trim() : '',
            key: keyEl ? keyEl.value.trim() : '',
            type: typeEl ? typeEl.value : 'string',
            value: valEl ? valEl.value : ''
          }));
        });
        self.updateData(cell.id, key, next);
      }

      ['globals'].forEach(function (key) {
        var table = self.propBody.querySelector('[data-paramtable="' + key + '"]');
        if (!table) return;

        /* 各列：值变化时提交，避免每敲一个字就重绘表格 */
        table.querySelectorAll('input,select').forEach(function (el) {
          el.addEventListener('change', function () { commit(key); });
          if (el.tagName === 'INPUT') {
            el.addEventListener('keydown', function (e) {
              if (e.key === 'Enter') { el.blur(); }
            });
          }
        });

        /* 删除某行 */
        table.querySelectorAll('.pt-del').forEach(function (btn) {
          btn.addEventListener('click', function () {
            var idx = Number(btn.closest('tr').dataset.idx);
            var list = (getPath(cell.data || {}, key) || []).slice();
            list.splice(idx, 1);
            self.updateData(cell.id, key, list);
            self.renderProps();
          });
        });

        /* 新增一行 */
        var addBtn = self.propBody.querySelector('[data-paramadd="' + key + '"]');
        if (addBtn) {
          addBtn.addEventListener('click', function () {
            var list = (getPath(cell.data || {}, key) || []).slice();
            list.push(Store.newParam('', '', 'string', ''));
            self.updateData(cell.id, key, list);
            self.renderProps();
          });
        }
      });
    },

    /* ---------- 内置算子规则表单 ----------
     * 值统一写入 cell.data.ruleConfig，规则表格额外支持增删行。
     */

    /** 把单个配置项写回 cell.data.ruleConfig */
    setOpField: function (cell, key, val) {
      cell.data = cell.data || {};
      if (!cell.data.ruleConfig) cell.data.ruleConfig = {};
      setPath(cell.data.ruleConfig, key, val);
      this.persist();
    },

    bindOpFields: function (cell) {
      var self = this;
      var body = this.propBody;
      if (!body) return;

      /* 普通输入项：change 时提交（避免每敲一个字就重建面板） */
      body.querySelectorAll('[data-opbind]').forEach(function (el) {
        var key = el.dataset.opbind;

        var commit = function () {
          var v;
          if (el.tagName === 'SELECT') {
            v = el.value;
          } else if (el.dataset.lines) {
            /* 多行文本 → 数组，过滤空行 */
            v = el.value.split('\n').map(function (s) { return s.trim(); })
              .filter(function (s) { return s !== ''; });
          } else if (el.type === 'number') {
            v = el.value === '' ? '' : Number(el.value);
          } else {
            v = el.value;
          }
          self.setOpField(cell, key, v);
        };

        el.addEventListener('change', commit);
        if (el.tagName === 'INPUT') {
          el.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { el.blur(); }
          });
        }
      });

      /* 规则表格：编辑单元格 */
      body.querySelectorAll('[data-ruletable]').forEach(function (tbl) {
        var key = tbl.dataset.ruletable;

        /* 找出该表的 schema 定义，用于判断哪些列是「下拉默认值」列。
           来源可能是：内置算子的 OP_SCHEMAS，或节点/连线自身的 schema。 */
        var fieldDef = null;
        var boundOp = cell.data && cell.data.opId ? Store.getOperator(cell.data.opId) : null;
        if (boundOp) {
          Store.opSchema(boundOp.opType).forEach(function (f) {
            if (f.key === key) fieldDef = f;
          });
        }
        if (!fieldDef) {
          var def = NodeDefs.get(cell.cellType);
          (def.schema || []).forEach(function (f) {
            if (f.key === key) fieldDef = f;
          });
        }
        var selectCols = (fieldDef && fieldDef.colTypes) || {};
        var colList = (fieldDef && fieldDef.cols) || [];

        /**
         * 过滤占位行。
         * 界面默认会渲染空行作占位，而下拉列会被浏览器自动选中第一项，
         * 因此不能只看「值为空」——「仅有下拉默认值、文本列全空」的行同样要剔除，
         * 否则会被写进 data.ruleConfig。
         */
        var keepFilled = function (rows) {
          return rows.filter(function (row) {
            return Object.keys(row).some(function (k) {
              if (row[k] === '') return false;
              var opts = selectCols[k];
              if (opts && row[k] === opts[0]) return false;   // 下拉默认值不算已填
              return true;
            });
          });
        };

        /** 读取表格中所有界面行（含占位空行） */
        var readAllRows = function () {
          return [].map.call(tbl.querySelectorAll('tbody tr'), function (tr) {
            var row = {};
            tr.querySelectorAll('.rt-cell').forEach(function (el) {
              row[el.dataset.col] = el.value.trim();
            });
            return row;
          });
        };

        /** 读取并过滤后的有效行 */
        var readRows = function () { return keepFilled(readAllRows()); };

        tbl.querySelectorAll('.rt-cell').forEach(function (el) {
          el.addEventListener('change', function () {
            self.setOpField(cell, key, readRows());
          });
        });

        /* 按界面行号删除：读全部界面行（含空行），删掉目标行后再过滤占位行 */
        tbl.querySelectorAll('[data-rueldel]').forEach(function (btn) {
          btn.addEventListener('click', function () {
            var idx = Number(btn.closest('tr').dataset.idx);
            var all = readAllRows();
            all.splice(idx, 1);

            /* 界面行数减少一行（不低于 1，保证始终有输入框） */
            self._ruleUiRows = self._ruleUiRows || {};
            self._ruleUiRows[key] = Math.max(1, all.length);
            self._ruleUiRowsFor = cell.id;

            self.setOpField(cell, key, keepFilled(all));
            self.renderProps();
          });
        });

        var addBtn = body.querySelector('[data-ruleadd="' + key + '"]');
        if (addBtn) {
          addBtn.addEventListener('click', function () {
            /* 只增加界面行数：空行不会写进数据，由 renderRuleTable 负责补足 */
            self._ruleUiRows = self._ruleUiRows || {};
            var cur = self._ruleUiRows[key] || tbl.querySelectorAll('tbody tr').length;
            self._ruleUiRows[key] = cur + 1;
            /* 先把已填内容落库（readRows 已过滤空行） */
            self.setOpField(cell, key, readRows());
            self.renderProps();
          });
        }
      });
    },

    /** 供 renderRuleTable 读取某个字段定义（算子 schema 或节点 schema） */
    _ruleFieldDef: function (cell, key) {
      var found = null;
      var op = cell && cell.data && cell.data.opId ? Store.getOperator(cell.data.opId) : null;
      if (op) {
        Store.opSchema(op.opType).forEach(function (f) { if (f.key === key) found = f; });
      }
      if (!found && cell) {
        var def = NodeDefs.get(cell.cellType);
        (def.schema || []).forEach(function (f) { if (f.key === key) found = f; });
      }
      return found;
    },

    /* ---------- 外部算子配置表单 ----------
     * 直接把面板里的改动写回算子对象（Store.saveOperator），
     * 算子被多个节点共享，因此改一处即全局生效。
     */
    bindOuterOpForm: function (cell) {
      var self = this;
      var body = this.propBody;
      if (!body) return;
      var op = cell.data && cell.data.opId ? Store.getOperator(cell.data.opId) : null;
      if (!op || Store.isBuiltinOperator(op)) return;

      /** 保存算子并刷新画布（算子名可能变了，需同步节点显示） */
      var commit = function () {
        if (op.name) cell.name = op.name;
        Store.saveOperator(op);
        self.persist();
        self.clearSizeCache(cell.id);
        self.render();
      };

      /* 普通字段：key 支持 "inputs.reqBodyType" 这类嵌套路径 */
      body.querySelectorAll('[data-opfield]').forEach(function (el) {
        var k = el.dataset.opfield;
        el.addEventListener('change', function () {
          var v = el.value;
          if (el.type === 'number') v = Number(v) || 0;
          if (k.indexOf('.') >= 0) setPath(op, k, v);
          else op[k] = v;
          commit();
          /* 名称变化时需要重绘面板标题，其余字段不必重建以免打断输入 */
          if (k === 'name') self.renderProps();
        });
      });

      /* 环境选择器（sql 数据库 / shell 执行环境）：
         选择即写回算子的 envId，并冗余保存名称/库名便于后端解析 */
      body.querySelectorAll('[data-envselect]').forEach(function (el) {
        var kind = el.dataset.envkind;
        el.addEventListener('change', function () {
          var env = Store.getEnv(el.value);
          op.envId = el.value;
          if (kind === 'sql') op.database = env ? (env.database || env.name) : '';
          else op.env = env ? env.name : '';
          commit();
          self.renderProps();   // 摘要与测试按钮可用性需要重渲染
        });
      });

      /* 环境管理入口：新建 / 编辑 / 删除 / 测试 */
      body.querySelectorAll('[data-envmanage]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var kind = btn.dataset.envmanage;
          Envs.open(kind, function (env) {
            op.envId = env.id;
            if (kind === 'sql') op.database = env.database || env.name;
            else op.env = env.name;
            commit();
            self.renderProps();
            UI.toast('已选用「' + env.name + '」', 'ok');
          });
        });
      });

      /* 连通性测试：结果同时更新到提示行，便于就地判断配置是否正确 */
      body.querySelectorAll('[data-envtest]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var kind = btn.dataset.envtest;
          var env = Store.getEnv(op.envId);
          if (!env) { UI.toast('请先选择' + (kind === 'sql' ? '数据库' : '执行环境'), 'warn'); return; }
          btn.disabled = true;
          btn.textContent = '测试中…';
          Api.env.test(env).then(function (r) {
            btn.disabled = false;
            btn.textContent = '测试连接';
            var tip = body.querySelector('[data-envsummary="' + kind + '"]');
            if (tip) {
              tip.className = 'tip env-summary ' + (r.ok ? 'test-ok' : 'test-err');
              tip.textContent = (r.ok ? '✓ 连接成功：' : '✕ 连接失败：') + r.msg
                + (r.latency ? '（' + r.latency + 'ms）' : '');
            }
            UI.toast(r.ok ? '连接成功：' + r.msg : '连接失败：' + r.msg, r.ok ? 'ok' : 'err');
          });
        });
      });

      /* 入参规则表：值直接写回算子对象 */
      body.querySelectorAll('[data-opruletable]').forEach(function (tbl) {
        var key = tbl.dataset.opruletable;

        /** 下拉列的默认值不算「已填」，过滤后才是有效行 */
        var keepFilled = function (rows) {
          return rows.filter(function (row) {
            return Object.keys(row).some(function (c) {
              var v = row[c];
              if (v === '' || v === undefined || v === null) return false;
              if (c === 'type' && v === '') return false;   // 下拉的「—」不算已填
              return true;
            });
          });
        };
        var readAll = function () {
          return [].map.call(tbl.querySelectorAll('tbody tr'), function (tr) {
            var row = {};
            tr.querySelectorAll('.rt-cell').forEach(function (el) {
              row[el.dataset.col] = el.value.trim();
            });
            return row;
          });
        };
        var save = function (rows) {
          setPath(op, key, keepFilled(rows));
          commit();
        };

        tbl.querySelectorAll('.rt-cell').forEach(function (el) {
          el.addEventListener('change', function () { save(readAll()); });
        });
        tbl.querySelectorAll('[data-opruledel]').forEach(function (btn) {
          btn.addEventListener('click', function () {
            var idx = Number(btn.closest('tr').dataset.idx);
            var all = readAll();
            all.splice(idx, 1);
            save(all);
            self.renderProps();
          });
        });
        var addBtn = body.querySelector('[data-opruleadd="' + key + '"]');
        if (addBtn) {
          addBtn.addEventListener('click', function () {
            var all = readAll();
            all.push({ name: '', key: '', type: '', jsonpathMapping: '', defaultValue: '' });
            save(all);
            self.renderProps();
          });
        }
      });

      /* key/value 表格（Headers、Query） */
      body.querySelectorAll('[data-opkvtable]').forEach(function (tbl) {
        var key = tbl.dataset.opkvtable;

        var readKv = function () {
          return [].map.call(tbl.querySelectorAll('tbody tr'), function (tr) {
            return {
              key: (tr.querySelector('.ok-k') || {}).value || '',
              value: (tr.querySelector('.ok-v') || {}).value || ''
            };
          }).filter(function (it) { return it.key !== '' || it.value !== ''; });
        };

        tbl.querySelectorAll('.ok-k, .ok-v').forEach(function (el) {
          el.addEventListener('change', function () { op[key] = readKv(); commit(); });
        });

        tbl.querySelectorAll('[data-opkvdel]').forEach(function (btn) {
          btn.addEventListener('click', function () {
            var idx = Number(btn.closest('tr').dataset.idx);
            var arr = readKv();
            arr.splice(idx, 1);
            op[key] = arr;
            commit();
            self.renderProps();
          });
        });

        var addBtn = body.querySelector('[data-opkvadd="' + key + '"]');
        if (addBtn) {
          addBtn.addEventListener('click', function () {
            var arr = readKv();
            arr.push({ key: '', value: '' });
            op[key] = arr;
            commit();
            self.renderProps();
          });
        }
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
  /** 按 "a.b.c" 路径写值，中间层级不存在时自动创建 */
  function setPath(obj, path, val) {
    if (!path) return;
    var parts = path.split('.');
    var cur = obj;
    for (var i = 0; i < parts.length - 1; i++) {
      var k = parts[i];
      if (cur[k] === null || typeof cur[k] !== 'object') cur[k] = {};
      cur = cur[k];
    }
    cur[parts[parts.length - 1]] = val;
  }

  global.Designer = Designer;
  global.getPath = getPath;
  global.setPath = setPath;
})(window);
