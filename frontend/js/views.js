/* ===== 视图逻辑 =====
 * ① Instances  实例列表（新建/进入编排/运行/运行记录/删除）
 * ② Runs       运行记录列表
 * ③ RunDetail  运行详情（编排图染色 + 实例日志 + 算子日志）
 * ④ Operators  算子注册（http / sql / shell 三种算子包）
 * ⑤ SchemaTool Schema 工具
 */
(function (global) {
  'use strict';

  /* ============================================================
     ① 实例列表
     ============================================================ */
  var Instances = {
    keyword: '',
    stateFilter: '',

    init: function () {
      var self = this;
      document.getElementById('btnNewInstance').onclick = function () { self.createDialog(); };
      document.getElementById('instSearch').addEventListener('input', function () {
        self.keyword = this.value.trim().toLowerCase();
        self.render();
      });
      document.getElementById('instStateFilter').addEventListener('change', function () {
        self.stateFilter = this.value;
        self.render();
      });
      this.render();
    },

    createDialog: function () {
      var self = this;
      UI.prompt({
        title: '新建实例',
        hint: '实例创建后可在编排页设计流程，并可多次运行以生成运行记录。',
        placeholder: '请输入实例名称，例如：用户注册流程',
        required: true,
        requiredMsg: '请输入实例名称',
        maxLength: 60,
        onOk: function (val) {
          var name = (val || '').trim();
          var inst = Store.createInstance(name, '');
          self.render();
          UI.toast('实例已创建，正在进入编排…', 'ok');
          setTimeout(function () { App.openDesigner(inst.id); }, 260);
        }
      });
    },

    render: function () {
      var grid = document.getElementById('instGrid');
      var empty = document.getElementById('instEmpty');
      var all = Store.listInstances();

      var list = all.filter(function (i) {
        if (this.stateFilter && (i.lastRunState || 'WAITE') !== this.stateFilter) return false;
        if (this.keyword) {
          var hay = (i.name + ' ' + i.planId + ' ' + (i.description || '')).toLowerCase();
          if (hay.indexOf(this.keyword) < 0) return false;
        }
        return true;
      }, this);

      document.getElementById('instCount').textContent = '共 ' + all.length + ' 个实例' +
        (list.length !== all.length ? '（筛选出 ' + list.length + ' 个）' : '');

      if (!all.length) { grid.innerHTML = ''; empty.style.display = 'flex'; return; }
      empty.style.display = 'none';

      grid.innerHTML = list.map(function (i) {
        var st = i.lastRunState || 'WAITE';
        var nodeCount = (i.cells || []).filter(function (c) { return !DslValidator.isEdge(c.cellType); }).length;
        return '<div class="inst-card st-' + st + '" data-id="' + i.id + '">'
          + '<div class="ic-head">'
          + '<span class="ic-name">' + esc(i.name) + '</span>'
          + '<span class="state-badge st-' + st + '">' + stateName(st) + '</span>'
          + '</div>'
          + '<div class="ic-desc">' + esc(i.description || '暂无描述') + '</div>'
          + '<div class="ic-meta">'
          + '<span class="meta">' + esc(i.planId) + '</span>'
          + '<span class="meta">' + nodeCount + ' 节点</span>'
          + '<span class="meta">运行 ' + (i.runCount || 0) + ' 次</span>'
          + '<span class="meta">' + fmtDate(i.updateTime) + '</span>'
          + '</div>'
          + '<div class="ic-foot">'
          + '<button class="ic-btn" data-act="design">编排</button>'
          + '<button class="ic-btn" data-act="run">运行</button>'
          + '<button class="ic-btn" data-act="runs">运行记录</button>'
          + '<span class="spacer"></span>'
          + '<button class="ic-btn danger" data-act="del">删除</button>'
          + '</div></div>';
      }).join('');

      grid.querySelectorAll('.inst-card').forEach(function (card) {
        var id = card.dataset.id;
        card.addEventListener('click', function (e) {
          var act = e.target.dataset && e.target.dataset.act;
          if (act === 'design') { App.openDesigner(id); }
          else if (act === 'run') { App.runInstance(id); }
          else if (act === 'runs') { App.openRuns(id); }
          else if (act === 'del') {
            e.stopPropagation();
            var i = Store.getInstance(id);
            UI.confirmAction('确定删除实例「' + i.name + '」及其全部运行记录？', function () {
              Api.instance.remove([id]).then(function () {
                Instances.render();
                UI.toast('已删除', 'ok');
              });
            });
            return;
          } else {
            App.openDesigner(id);
          }
        });
      });
    }
  };

  /* ============================================================
     ② 运行记录
     ============================================================ */
  var Runs = {
    instId: '',
    stateFilter: '',

    init: function () {
      var self = this;
      document.getElementById('btnRunsBack').onclick = function () { App.show('instances'); };
      document.getElementById('btnRunsRun').onclick = function () {
        if (self.instId) App.runInstance(self.instId);
      };
      document.getElementById('runStateFilter').addEventListener('change', function () {
        self.stateFilter = this.value;
        self.render();
      });
    },

    open: function (instId) {
      this.instId = instId;
      this.stateFilter = '';
      document.getElementById('runStateFilter').value = '';
      this.render();
    },

    render: function () {
      var inst = Store.getInstance(this.instId);
      var tbody = document.getElementById('runsTbody');
      var empty = document.getElementById('runsEmpty');
      if (!inst) return;

      document.getElementById('runsDesc').textContent =
        '实例「' + inst.name + '」· ' + inst.planId + '　每次运行生成一条记录，点击可查看编排图与两级日志。';

      var all = Store.listRuns(this.instId);
      var list = this.stateFilter
        ? all.filter(function (r) { return r.state === this.stateFilter; }, this)
        : all;

      document.getElementById('runsCount').textContent = '共 ' + all.length + ' 条记录';

      if (!all.length) { tbody.innerHTML = ''; empty.style.display = 'flex'; return; }
      empty.style.display = 'none';

      tbody.innerHTML = list.map(function (r) {
        var inputSum = summarize(r.inputs);
        return '<tr data-run="' + r.id + '">'
          + '<td class="td-seq">#' + r.seq + '</td>'
          + '<td class="td-mono">' + fmtDateTime(r.startTime) + '</td>'
          + '<td class="td-mono">' + (r.duration ? r.duration + ' ms' : '—') + '</td>'
          + '<td><span class="state-badge st-' + r.state + '">' + stateName(r.state) + '</span></td>'
          + '<td class="td-mono">' + esc(inputSum) + '</td>'
          + '<td class="td-act"><button class="ic-btn" data-act="detail">查看详情 →</button></td>'
          + '</tr>';
      }).join('');

      tbody.querySelectorAll('tr').forEach(function (tr) {
        tr.addEventListener('click', function (e) {
          var act = e.target.dataset && e.target.dataset.act;
          if (act === 'detail' || !act) App.openRunDetail(Runs.instId, tr.dataset.run);
        });
      });
    }
  };

  /* ============================================================
     ③ 运行详情（编排图染色 + 实例日志 + 算子日志）
     ============================================================ */
  var RunDetail = {
    instId: '',
    runId: '',
    run: null,
    selectedNodeId: '',
    scale: 1, pan: { x: 0, y: 0 },
    autoScroll: true,
    _initialized: false,

    init: function () {
      var self = this;
      document.getElementById('btnRdBack').onclick = function () { App.openRuns(self.instId); };

      document.querySelectorAll('.rd-tab').forEach(function (t) {
        t.onclick = function () {
          document.querySelectorAll('.rd-tab').forEach(function (x) { x.classList.remove('active'); });
          document.querySelectorAll('.rd-pane').forEach(function (x) { x.classList.remove('active'); });
          t.classList.add('active');
          document.getElementById('rdPane' + cap(t.dataset.rdtab)).classList.add('active');
        };
      });

      document.getElementById('logLevelFilter').addEventListener('change', function () {
        self.renderLogs();
      });
      document.getElementById('btnLogAutoScroll').onclick = function () {
        self.autoScroll = !self.autoScroll;
        this.textContent = '自动滚动：' + (self.autoScroll ? '开' : '关');
        if (self.autoScroll) self.scrollLogBottom();
      };

      /* 编排图平移/缩放 */
      var graph = document.getElementById('rdGraph');
      graph.addEventListener('mousedown', function (e) {
        if (e.target.closest('.node')) return;
        if (e.button === 1 || e.button === 2) {
          self._panning = { sx: e.clientX, sy: e.clientY, ox: self.pan.x, oy: self.pan.y };
          e.preventDefault();
        }
      });
      graph.addEventListener('contextmenu', function (e) { e.preventDefault(); });
      window.addEventListener('mousemove', function (e) {
        if (self._panning) {
          self.pan.x = self._panning.ox + (e.clientX - self._panning.sx);
          self.pan.y = self._panning.oy + (e.clientY - self._panning.sy);
          self.applyTransform();
        }
      });
      window.addEventListener('mouseup', function () { self._panning = null; });
      graph.addEventListener('wheel', function (e) {
        if (!e.ctrlKey && !e.metaKey) return;
        e.preventDefault();
        self.scale = Math.max(0.3, Math.min(2, self.scale + (e.deltaY > 0 ? -0.1 : 0.1)));
        self.applyTransform();
      }, { passive: false });

      this._initialized = true;
    },

    open: function (instId, runId) {
      if (!this._initialized) this.init();
      this.instId = instId;
      this.runId = runId;
      this.run = Store.getRun(instId, runId);
      this.selectedNodeId = '';
      this.scale = 1; this.pan = { x: 0, y: 0 };
      if (!this.run) { UI.toast('运行记录不存在', 'err'); return; }

      this.renderHead();
      this.renderGraph();
      this.renderLogs();
      this.renderNodePanel();
      this.fit();
    },

    renderHead: function () {
      var r = this.run;
      var s = summary(r);
      document.getElementById('rdSeq').textContent = '#' + r.seq;
      document.getElementById('rdTime').textContent = fmtDateTime(r.startTime);
      var sb = document.getElementById('rdState');
      sb.className = 'state-badge st-' + r.state;
      sb.textContent = stateName(r.state);
      document.getElementById('rdStats').innerHTML =
        '<span>共 <b>' + s.total + '</b> 节点</span>'
        + '<span class="ok">成功 <b>' + s.success + '</b></span>'
        + '<span class="bad">失败 <b>' + s.fail + '</b></span>'
        + '<span>跳过 <b>' + s.skip + '</b></span>'
        + '<span>耗时 <b>' + (r.duration || 0) + ' ms</b></span>';
    },

    renderGraph: function () {
      var self = this;
      var r = this.run;
      var cells = r.cells || [];
      var nodes = cells.filter(function (c) { return !DslValidator.isEdge(c.cellType); });
      var edges = cells.filter(function (c) { return DslValidator.isEdge(c.cellType); });

      /* 节点 */
      var html = nodes.map(function (n) {
        var def = NodeDefs.get(n.cellType);
        var cat = NodeDefs.categoryOf(n.cellType);
        var st = (r.nodeStates[n.id] && r.nodeStates[n.id].state) || 'WAIT';
        var active = n.id === self.selectedNodeId ? ' active-node' : '';
        return '<div class="node rst-' + st + active + '" data-rid="' + n.id + '" '
          + 'style="left:' + (n.x || 0) + 'px;top:' + (n.y || 0) + 'px">'
          + '<div class="node-accent" style="background:' + cat.color + '"></div>'
          + '<div class="node-main">'
          + '<span class="node-ico" style="background:' + cat.color + '">' + esc(def.icon) + '</span>'
          + '<span class="node-meta">'
          + '<span class="node-name">' + esc(n.name || def.name) + '</span>'
          + '<span class="node-type">' + esc(n.cellType) + '</span>'
          + '</span></div>'
          + '<div class="node-badges">'
          + '<span class="nbadge">' + stateName(st) + '</span>'
          + (r.nodeStates[n.id] ? '<span class="nbadge">' + (r.nodeStates[n.id].duration || 0) + 'ms</span>' : '')
          + '</div></div>';
      }).join('');
      document.getElementById('rdNodesLayer').innerHTML = html;

      document.getElementById('rdNodesLayer').querySelectorAll('.node').forEach(function (el) {
        el.addEventListener('mousedown', function (e) {
          e.stopPropagation();
          self.selectNode(el.dataset.rid);
        });
      });

      /* 连线 */
      var svg = document.getElementById('rdEdgesGroup');
      var ns = 'http://www.w3.org/2000/svg';
      while (svg.firstChild) svg.removeChild(svg.firstChild);
      edges.forEach(function (edge) {
        var s = self.rdPort(edge.source && edge.source.cell, nodes, true);
        var t = self.rdPort(edge.target && edge.target.cell, nodes, false);
        if (!s || !t) return;
        var dx = Math.max(42, Math.abs(t.x - s.x) * 0.45);
        var d = 'M' + s.x + ',' + s.y + ' C' + (s.x + dx) + ',' + s.y + ' '
          + (t.x - dx) + ',' + t.y + ' ' + t.x + ',' + t.y;
        var p = document.createElementNS(ns, 'path');
        p.setAttribute('d', d);
        p.setAttribute('fill', 'none');
        p.setAttribute('stroke', '#94a3b8');
        p.setAttribute('stroke-width', '1.8');
        p.setAttribute('marker-end', 'url(#rd-arrow)');
        if (edge.cellType === 'edge_decision') { p.setAttribute('stroke', '#f59e0b'); p.setAttribute('stroke-dasharray', '6 4'); }
        if (edge.cellType === 'edge_loop') { p.setAttribute('stroke', '#14b8a6'); p.setAttribute('stroke-dasharray', '2 4'); }
        if (edge.cellType === 'edge_compensate') { p.setAttribute('stroke', '#ef4444'); p.setAttribute('stroke-dasharray', '7 4'); }
        svg.appendChild(p);
      });
    },

    rdPort: function (nodeId, nodes, isOut) {
      var n = nodes.filter(function (x) { return x.id === nodeId; })[0];
      if (!n) return null;
      var el = document.querySelector('#rdNodesLayer .node[data-rid="' + nodeId + '"]');
      var w = el ? el.offsetWidth : 160;
      var h = el ? el.offsetHeight : 62;
      return { x: (n.x || 0) + (isOut ? w : 0), y: (n.y || 0) + h / 2 };
    },

    applyTransform: function () {
      var t = 'translate(' + this.pan.x + 'px,' + this.pan.y + 'px) scale(' + this.scale + ')';
      document.getElementById('rdNodesLayer').style.transform = t;
      document.getElementById('rdEdgesGroup').setAttribute('transform',
        'translate(' + this.pan.x + ',' + this.pan.y + ') scale(' + this.scale + ')');
    },

    fit: function () {
      var self = this;
      setTimeout(function () {
        var r = self.run;
        var nodes = (r.cells || []).filter(function (c) { return !DslValidator.isEdge(c.cellType); });
        if (!nodes.length) return;
        var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        nodes.forEach(function (n) {
          minX = Math.min(minX, n.x || 0); minY = Math.min(minY, n.y || 0);
          maxX = Math.max(maxX, (n.x || 0) + 210); maxY = Math.max(maxY, (n.y || 0) + 100);
        });
        var box = document.getElementById('rdGraph').getBoundingClientRect();
        var pad = 60;
        var sx = (box.width - pad * 2) / Math.max(1, maxX - minX);
        var sy = (box.height - pad * 2) / Math.max(1, maxY - minY);
        self.scale = Math.max(0.3, Math.min(1.15, Math.min(sx, sy)));
        self.pan = { x: pad - minX * self.scale, y: pad - minY * self.scale };
        self.applyTransform();
      }, 40);
    },

    selectNode: function (nodeId) {
      this.selectedNodeId = nodeId;
      document.querySelectorAll('#rdNodesLayer .node').forEach(function (el) {
        el.classList.toggle('active-node', el.dataset.rid === nodeId);
      });
      this.renderNodePanel();
    },

    renderLogs: function () {
      var r = this.run;
      var level = document.getElementById('logLevelFilter').value;
      var logs = (r.logs || []).filter(function (l) { return !level || l.level === level; });

      document.getElementById('logCount').textContent = logs.length + ' 条';
      document.getElementById('rdLogCount').textContent = (r.logs || []).length;

      var box = document.getElementById('logList');
      if (!logs.length) {
        box.innerHTML = '<div class="log-empty">暂无日志</div>';
        return;
      }
      box.innerHTML = logs.map(function (l) {
        return '<div class="log-row">'
          + '<span class="log-time">' + fmtTime(l.time) + '</span>'
          + '<span class="log-level ' + l.level + '">' + l.level + '</span>'
          + '<span class="log-node">' + esc(l.nodeName || '-') + '</span>'
          + '<span class="log-msg">' + esc(l.message) + '</span>'
          + '</div>';
      }).join('');
      if (this.autoScroll) this.scrollLogBottom();
    },

    scrollLogBottom: function () {
      var box = document.getElementById('logList');
      setTimeout(function () { box.scrollTop = box.scrollHeight; }, 30);
    },

    renderNodePanel: function () {
      var body = document.getElementById('rnpBody');
      var iconEl = document.getElementById('rnpIcon');
      var r = this.run;

      if (!this.selectedNodeId) {
        iconEl.textContent = '◈';
        iconEl.style.background = '';
        iconEl.style.color = '';
        document.getElementById('rnpTitle').textContent = '算子运行日志';
        document.getElementById('rnpSub').textContent = '点击左侧编排图中的算子查看';
        document.getElementById('rnpStats').innerHTML = '';
        body.innerHTML = '<div class="insp-empty"><div class="ie-ico">◈</div>'
          + '<p>点击编排图中的<strong>算子节点</strong><br>查看它的运行日志</p></div>';
        return;
      }

      var cell = (r.cells || []).filter(function (c) { return c.id === this.selectedNodeId; }, this)[0];
      if (!cell) return;
      var def = NodeDefs.get(cell.cellType);
      var cat = NodeDefs.categoryOf(cell.cellType);
      var ns = r.nodeStates[this.selectedNodeId] || { state: 'WAIT' };
      var logs = r.nodeLogs[this.selectedNodeId] || [];

      iconEl.textContent = def.icon;
      iconEl.style.background = cat.color;
      iconEl.style.color = '#fff';
      document.getElementById('rnpTitle').textContent = cell.name || def.name;
      document.getElementById('rnpSub').textContent = cell.cellType + (cell.data && cell.data.opId ? ' · ' + cell.data.opId.slice(0, 8) : '');

      document.getElementById('rnpStats').innerHTML =
        '<div class="inst-stat"><span class="k">状态</span>'
        + '<span class="v state-' + ns.state + '">' + stateName(ns.state) + '</span></div>'
        + '<div class="inst-stat"><span class="k">耗时</span><span class="v">' + (ns.duration || 0) + 'ms</span></div>'
        + '<div class="inst-stat"><span class="k">日志</span><span class="v">' + logs.length + '</span></div>';

      var html = '';
      /* 入参 */
      html += '<div class="rnp-section"><div class="rnp-section-title">入参</div>'
        + '<pre class="rnp-panel">' + esc(pretty(ns.inputs)) + '</pre></div>';
      /* 出参 */
      html += '<div class="rnp-section"><div class="rnp-section-title">出参</div>'
        + '<pre class="rnp-panel">' + esc(pretty(ns.outputs)) + '</pre></div>';
      /* 节点日志 */
      html += '<div class="rnp-section"><div class="rnp-section-title">算子日志（' + logs.length + '）</div>';
      if (!logs.length) {
        html += '<div class="empty-hint">该算子暂无日志</div>';
      } else {
        html += '<div class="rnp-log-list">' + logs.map(function (l) {
          return '<div class="rnp-log-item">'
            + '<span class="rnp-log-time">' + fmtTime(l.time) + '</span>'
            + '<span class="rnp-log-level ' + l.level + '">' + l.level + '</span>'
            + '<span class="rnp-log-msg">' + esc(l.message) + '</span>'
            + '</div>';
        }).join('') + '</div>';
      }
      html += '</div>';
      /* 异常 */
      if (ns.error) {
        html += '<div class="rnp-section"><div class="rnp-section-title">异常信息</div>'
          + '<pre class="rnp-panel" style="color:#f87171">' + esc(ns.error) + '</pre></div>';
      }
      body.innerHTML = html;
    }
  };

  /* ============================================================
     ④ 环境配置（Shell 执行环境 / SQL 数据库连接）
     ------------------------------------------------------------
     shell / sql 算子的「执行环境」「数据库」不再写死在表单里，
     统一由本模块 CRUD 维护，并支持连通性测试。
     ============================================================ */
  var Envs = {
    kind: 'shell',          // 当前查看的分类
    editingId: '',          // 正在编辑的环境 id（空=新建）
    _hostEl: null,          // 弹窗宿主
    _draft: null,           // 编辑中的草稿（避免未保存就写库）
    _onPicked: null,        // 「选用此配置」回调（由算子表单传入）
    _collect: null,         // 当前表单的收集函数

    /** 打开环境管理弹窗 */
    open: function (kind, onPicked) {
      var self = this;
      this.kind = Store.ENV_KINDS[kind] ? kind : 'shell';
      this.editingId = '';
      this._onPicked = onPicked || null;
      this._draft = null;
      this._collect = null;

      /* 首次使用预置示例，避免空表 */
      Store.seedEnvs();

      var host = document.getElementById('envModalMask');
      if (!host) {
        host = document.createElement('div');
        host.id = 'envModalMask';
        host.className = 'modal-mask env-modal';
        host.innerHTML =
          '<div class="modal env-modal-box">'
          + '<div class="modal-head">'
          + '<span>环境配置</span>'
          + '<button class="modal-x" id="envModalClose" title="关闭">✕</button>'
          + '</div>'
          + '<div class="env-body">'
          + '<div class="env-list-col">'
          + '<div class="env-kind-seg">'
          + '<button class="seg-item" data-envkind="shell">Shell 执行环境</button>'
          + '<button class="seg-item" data-envkind="sql">数据库连接</button>'
          + '</div>'
          + '<div class="env-list" id="envList"></div>'
          + '<button class="btn btn-sm btn-block" id="envAdd">＋ 新建</button>'
          + '</div>'
          + '<div class="env-form-col" id="envForm"></div>'
          + '</div>'
          + '</div>';
        document.body.appendChild(host);
        this._hostEl = host;

        host.querySelector('#envModalClose').onclick = function () { self.close(); };
        host.onclick = function (e) { if (e.target === host) self.close(); };
        host.querySelector('#envAdd').onclick = function () { self.startNew(); };
        host.querySelectorAll('[data-envkind]').forEach(function (b) {
          b.onclick = function () {
            self.kind = b.dataset.envkind;
            self.editingId = '';
            self._draft = null;
            self.render();
          };
        });
      }
      host.classList.add('open');
      this.render();
    },

    close: function () {
      var host = document.getElementById('envModalMask');
      if (host) host.classList.remove('open');
      this._draft = null;
      this.editingId = '';
    },

    startNew: function () {
      this.editingId = '';
      this._draft = Store.newEnv(this.kind);
      this._draft.__new = true;
      this.renderForm();
    },

    startEdit: function (id) {
      var env = Store.getEnv(id);
      if (!env) return;
      this.editingId = id;
      this._draft = JSON.parse(JSON.stringify(env));
      this.render();
    },

    render: function () {
      var self = this;
      var host = document.getElementById('envModalMask');
      if (!host) return;

      host.querySelectorAll('[data-envkind]').forEach(function (b) {
        b.classList.toggle('active', b.dataset.envkind === self.kind);
      });

      var list = Store.listEnvs(this.kind);
      var box = host.querySelector('#envList');
      if (!list.length) {
        box.innerHTML = '<div class="env-empty">暂无配置，点击下方「新建」添加</div>';
      } else {
        box.innerHTML = list.map(function (e) {
          var picked = e.id === self.editingId;
          var t = e.lastTest;
          var badge = '';
          if (t) {
            badge = '<span class="env-badge ' + (t.ok ? 'ok' : 'err') + '" title="'
              + esc(t.msg) + ' · ' + fmtTime(t.time) + '">'
              + (t.ok ? '可达' : '不可达') + '</span>';
          }
          return '<div class="env-item' + (picked ? ' active' : '') + '" data-envid="' + esc(e.id) + '">'
            + '<div class="env-item-main">'
            + '<div class="env-item-name">' + esc(e.name || '(未命名)') + badge + '</div>'
            + '<div class="env-item-sub">' + esc(Store.envSummary(e)) + '</div>'
            + '</div>'
            + '<button class="env-item-del" data-envdel="' + esc(e.id) + '" title="删除">✕</button>'
            + '</div>';
        }).join('');

        box.querySelectorAll('.env-item').forEach(function (it) {
          it.onclick = function (ev) {
            if (ev.target.dataset.envdel) return;
            self.startEdit(it.dataset.envid);
          };
        });
        box.querySelectorAll('[data-envdel]').forEach(function (btn) {
          btn.onclick = function (ev) {
            ev.stopPropagation();
            self.remove(btn.dataset.envdel);
          };
        });
      }
      this.renderForm();
    },

    renderForm: function () {
      var host = document.getElementById('envModalMask');
      if (!host) return;
      var box = host.querySelector('#envForm');
      var d = this._draft;
      if (!d) {
        box.innerHTML = '<div class="env-empty tall">'
          + '从左侧选择一条配置进行编辑，或点击「＋ 新建」。</div>';
        return;
      }

      var def = Store.ENV_KINDS[d.kind];
      var fields = def.fields;
      /* password 与 privateKey 联动：选密码则隐藏私钥，反之亦然 */
      fields = fields.filter(function (f) {
        if (f.key === 'privateKey' && d.authType !== 'privateKey') return false;
        if (f.key === 'password' && d.authType === 'privateKey') return false;
        return true;
      });

      var html = '<div class="env-form-head">'
        + '<span class="env-form-title">' + (d.__new ? '新建' : '编辑') + ' · ' + esc(def.name) + '</span>'
        + '</div>';

      html += '<div class="ifield"><label>环境名称 <span class="req">*</span></label>'
        + '<input type="text" id="envName" value="' + esc(d.name || '')
        + '" placeholder="如 linux-prod-01"></div>';
      html += '<div class="ifield"><label>说明</label>'
        + '<input type="text" id="envDesc" value="' + esc(d.description || '')
        + '" placeholder="用途备注（可选）"></div>';

      fields.forEach(function (f) {
        var v = d[f.key] === undefined || d[f.key] === null ? '' : d[f.key];
        var req = f.required ? ' <span class="req">*</span>' : '';
        html += '<div class="ifield"><label>' + esc(f.label) + req + '</label>';
        if (f.type === 'select') {
          html += '<select data-envfield="' + f.key + '">'
            + (f.options || []).map(function (o) {
              return '<option' + (String(v) === o ? ' selected' : '') + '>' + esc(o) + '</option>';
            }).join('') + '</select>';
        } else if (f.type === 'number') {
          html += '<input type="number" data-envfield="' + f.key + '" value="' + esc(v) + '">';
        } else if (f.type === 'password') {
          html += '<input type="password" data-envfield="' + f.key + '" value="' + esc(v)
            + '" autocomplete="new-password">';
        } else if (f.type === 'textarea') {
          html += '<textarea rows="4" data-envfield="' + f.key + '"'
            + ' placeholder="粘贴 PEM 私钥内容">' + esc(v) + '</textarea>';
        } else {
          html += '<input type="text" data-envfield="' + f.key + '" value="' + esc(v) + '"'
            + (f.placeholder ? ' placeholder="' + esc(f.placeholder) + '"' : '') + '>';
        }
        html += '</div>';
      });

      /* 连接串预览 */
      html += '<div class="env-preview" id="envPreview">'
        + '<span class="k">连接：</span><code>' + esc(Store.envSummary(d)) + '</code></div>';

      /* 上一次测试结果 */
      if (d.lastTest) {
        html += '<div class="env-test-result ' + (d.lastTest.ok ? 'ok' : 'err') + '">'
          + (d.lastTest.ok ? '✓ ' : '✕ ') + esc(d.lastTest.msg || '')
          + '<span class="env-test-time">' + fmtTime(d.lastTest.time) + '</span></div>';
      } else {
        html += '<div class="env-test-result" id="envTestResult">尚未测试连通性</div>';
      }

      html += '<div class="env-actions">'
        + '<button class="btn btn-sm" id="envTest">测试连接</button>'
        + '<button class="btn btn-sm btn-primary" id="envSave">保存</button>'
        + (d.__new ? '' : '<button class="btn btn-sm" id="envDup">另存为</button>')
        + '</div>';

      /* 「直接选用」：从算子表单里打开时，保存后可直接回填 */
      if (this._onPicked && !d.__new) {
        html += '<div class="tip">提示：点「选用此配置」会立即回填到所在算子表单。</div>';
        html += '<button class="btn btn-sm btn-block" id="envPick">选用此配置</button>';
      }

      box.innerHTML = html;
      this.bindForm();
    },

    bindForm: function () {
      var self = this;
      var box = document.getElementById('envForm');
      if (!box) return;
      var d = this._draft;

      function collect() {
        var nameEl = box.querySelector('#envName');
        if (nameEl) d.name = nameEl.value;
        var descEl = box.querySelector('#envDesc');
        if (descEl) d.description = descEl.value;
        box.querySelectorAll('[data-envfield]').forEach(function (el) {
          var f = el.dataset.envfield;
          d[f] = (el.type === 'number')
            ? (el.value === '' ? '' : Number(el.value))
            : el.value;
        });
        var pv = box.querySelector('#envPreview code');
        if (pv) pv.textContent = Store.envSummary(d);
        return d;
      }
      this._collect = collect;

      /* 任一字段改动都刷新草稿与连接串预览；
         认证方式额外触发一次重渲染，用于在「密码 / 私钥」之间切换字段 */
      box.querySelectorAll('input,select,textarea').forEach(function (el) {
        var evt = (el.tagName === 'SELECT') ? 'change' : 'input';
        el.addEventListener(evt, function () {
          collect();
          if (el.dataset.envfield === 'authType') self.renderForm();
        });
      });

      var testBtn = box.querySelector('#envTest');
      if (testBtn) testBtn.onclick = function () { self.test(); };

      var saveBtn = box.querySelector('#envSave');
      if (saveBtn) saveBtn.onclick = function () { self.save(); };

      var dupBtn = box.querySelector('#envDup');
      if (dupBtn) {
        dupBtn.onclick = function () {
          collect();
          self.editingId = '';
          d.id = '';
          d.__new = true;
          d.name = (d.name || '') + '-copy';
          self.renderForm();
          UI.toast('已复制为新配置，保存后生效', 'ok');
        };
      }

      var pickBtn = box.querySelector('#envPick');
      if (pickBtn) {
        pickBtn.onclick = function () {
          if (self._onPicked) { self._onPicked(d); self.close(); }
        };
      }
    },

    test: function () {
      var self = this;
      var d = this._collect ? this._collect() : this._draft;
      /* 未保存的配置没有 id，测完无法回写结果 —— 先提示但不阻断测试 */
      var box = document.getElementById('envForm');
      var res = box.querySelector('#envTestResult')
        || box.querySelector('.env-test-result');
      var btn = box.querySelector('#envTest');
      if (btn) { btn.disabled = true; btn.textContent = '测试中…'; }
      if (res) { res.className = 'env-test-result'; res.textContent = '正在连接…'; }

      /* 新建未保存时先落库，让 lastTest 有归属；保存失败则直接提示 */
      var target = d;
      if (!target.id) {
        var saved = Store.saveEnv(JSON.parse(JSON.stringify(d)));
        if (!saved.ok) {
          if (btn) { btn.disabled = false; btn.textContent = '测试连接'; }
          if (res) { res.className = 'env-test-result err'; res.textContent = '✕ ' + saved.msg; }
          UI.toast(saved.msg, 'warn');
          return;
        }
        target = saved.env;
        self.editingId = target.id;
        self._draft = JSON.parse(JSON.stringify(target));
        self._draft.__new = false;
      }

      Api.env.test(target).then(function (r) {
        if (btn) { btn.disabled = false; btn.textContent = '测试连接'; }
        /* Api.env.test 内部已把结果写入 Store（markEnvTest），
           这里只需从 Store 同步回草稿，避免用本地时间覆盖服务端返回的时刻。 */
        var latest = Store.getEnv(target.id);
        if (latest) {
          self._draft = JSON.parse(JSON.stringify(latest));
          self._draft.__new = false;
        }
        var el = document.getElementById('envForm').querySelector('.env-test-result');
        if (el) {
          el.className = 'env-test-result ' + (r.ok ? 'ok' : 'err');
          el.innerHTML = (r.ok ? '✓ ' : '✕ ') + esc(r.msg)
            + (r.latency ? '<span class="env-test-time">' + r.latency + 'ms</span>' : '');
        }
        UI.toast(r.ok ? '连接成功：' + r.msg : '连接失败：' + r.msg, r.ok ? 'ok' : 'err');
        self.render();
      });
    },

    save: function () {
      var d = this._collect ? this._collect() : this._draft;
      var plain = JSON.parse(JSON.stringify(d));
      delete plain.__new;
      var r = Store.saveEnv(plain);
      if (!r.ok) { UI.toast(r.msg, 'warn'); return; }
      this.editingId = r.env.id;
      this._draft = JSON.parse(JSON.stringify(r.env));
      this._draft.__new = false;
      UI.toast('配置已保存', 'ok');
      this.render();
    },

    remove: function (id) {
      var self = this;
      var env = Store.getEnv(id);
      if (!env) return;
      UI.confirmAction('确定删除环境「' + (env.name || '') + '」？\n被算子引用时将无法删除。',
        function () {
          var r = Store.removeEnv(id);
          if (!r.ok) { UI.toast(r.msg, 'warn'); return; }
          if (self.editingId === id) { self.editingId = ''; self._draft = null; }
          UI.toast('已删除', 'ok');
          self.render();
        }, { title: '删除环境配置', danger: true, okText: '删除' });
    },

    /**
     * 在算子表单里渲染「环境选择器」：下拉 + 管理按钮 + 测试按钮。
     * @param {string} kind  shell | sql
     * @param {string} envId 当前选中的环境 id
     * @param {string} bindKey 写回算子的字段名（envId）
     */
    selectHtml: function (kind, envId, bindKey) {
      var list = Store.listEnvs(kind);
      var cur = envId ? Store.getEnv(envId) : null;
      var html = '<div class="ifield ifield-env"><label>'
        + (kind === 'shell' ? '执行环境' : '数据库') + ' <span class="req">*</span></label>';
      html += '<div class="env-picker">';
      html += '<select data-envselect="' + esc(bindKey || 'envId') + '" data-envkind="' + esc(kind) + '">';
      html += '<option value="">' + (list.length ? '请选择…' : '（暂无配置，请先新建）') + '</option>';
      list.forEach(function (e) {
        html += '<option value="' + esc(e.id) + '"' + (e.id === envId ? ' selected' : '')
          + '>' + esc(e.name) + '</option>';
      });
      html += '</select>';
      html += '<button type="button" class="btn btn-sm" data-envmanage="' + esc(kind) + '">管理</button>';
      html += '<button type="button" class="btn btn-sm" data-envtest="' + esc(kind) + '"'
        + (cur ? '' : ' disabled') + '>测试连接</button>';
      html += '</div>';
      if (cur) {
        html += '<div class="tip env-summary" data-envsummary="' + esc(kind) + '">'
          + esc(Store.envSummary(cur)) + '</div>';
      } else if (envId) {
        /* 引用的配置已被删除：明确提示，避免静默回落到「未选择」 */
        html += '<div class="tip env-summary test-err" data-envsummary="' + esc(kind) + '">'
          + '✕ 原引用的' + (kind === 'shell' ? '执行环境' : '数据库')
          + '已不存在，请重新选择</div>';
      } else {
        html += '<div class="tip env-summary" data-envsummary="' + esc(kind) + '">'
          + '尚未选择' + (kind === 'shell' ? '执行环境' : '数据库') + '，点「管理」新建或选择。</div>';
      }
      return html + '</div>';
    }
  };

  /* ============================================================
     ⑤ 算子注册（http / sql / shell）
     ============================================================ */
  var Operators = {
    keyword: '',
    typeFilter: '',
    editingId: '',

    init: function () {
      var self = this;
      document.getElementById('btnNewOperator').onclick = function () { self.openDrawer(''); };
      document.getElementById('opSearch').addEventListener('input', function () {
        self.keyword = this.value.trim().toLowerCase();
        self.render();
      });
      document.querySelectorAll('#opTypeSeg .seg-item').forEach(function (b) {
        b.onclick = function () {
          document.querySelectorAll('#opTypeSeg .seg-item').forEach(function (x) { x.classList.remove('active'); });
          b.classList.add('active');
          self.typeFilter = b.dataset.optype;
          self.render();
        };
      });

      /* 抽屉 */
      document.getElementById('opDrawerClose').onclick = function () { self.closeDrawer(); };
      document.getElementById('opDrawerMask').onclick = function () { self.closeDrawer(); };
      document.getElementById('btnOpCancel2').onclick = function () { self.closeDrawer(); };
      document.getElementById('btnOpSave2').onclick = function () { self.save(); };

      /* 类型切换 → 切换表单 */
      document.getElementById('opType2').addEventListener('change', function () { self.switchForm(this.value); });

      /* HTTP KV 编辑 */
      document.getElementById('btnAddOpHeader').onclick = function () { self.addKv('headers'); };
      document.getElementById('btnAddOpQuery').onclick = function () { self.addKv('query'); };

      this.render();
    },

    openDrawer: function (opId) {
      var self = this;
      this.editingId = opId || '';
      var op = opId ? Store.getOperator(opId) : null;

      var isBuiltin = !!(op && op.builtin);
      document.getElementById('opDrawerTitle').textContent =
        op ? (isBuiltin ? '编辑内置算子' : '编辑算子') : '新建算子';
      document.getElementById('opName2').value = op ? op.name : '';
      document.getElementById('opDesc2').value = op ? (op.description || '') : '';
      document.getElementById('opType2').value = op ? op.opType : 'http';
      document.getElementById('opType2').disabled = !!op;   // 编辑时不允许改类型

      /* 使用范围：内置算子不参与复用，隐藏该项 */
      var scopeBox = document.getElementById('opScopeBox');
      scopeBox.style.display = isBuiltin ? 'none' : '';
      if (!isBuiltin) {
        var scope = op ? (Store.isSharedOperator(op) ? 'shared' : 'local') : 'local';
        document.querySelectorAll('input[name="opScope"]').forEach(function (r) {
          r.checked = (r.value === scope);
        });
      }

      this.switchForm(op ? op.opType : 'http');

      /* 补偿算子复用同族外部算子的表单 */
      var baseT = op ? Store.baseOpType(op.opType) : 'http';

      if (op) {
        if (baseT === 'http') {
          document.getElementById('opUrl2').value = op.url || '';
          document.getElementById('opMethod2').value = op.requestType || op.method || 'GET';
          document.getElementById('opContentType2').value =
            (op.outputs && op.outputs.contentType) || op.contentType || 'application/json';
          document.getElementById('opBody2').value =
            (op.inputs && (op.inputs.reqBodyOther || op.inputs.bodyJsonSchema)) || op.body || '';
          /* 入参规则 → 抽屉里的 kv 行 */
          this._headers = ((op.inputs && op.inputs.reqHeaders) || op.headers || [])
            .map(function (x) { return { k: x.key || x.name || '', v: x.value || x.defaultValue || '' }; });
          this._query = ((op.inputs && op.inputs.reqQuery) || op.query || [])
            .map(function (x) { return { k: x.key || x.name || '', v: x.value || x.defaultValue || '' }; });
        } else if (baseT === 'sql') {
          this.renderEnvPicker('sql', op.envId || '');
          document.getElementById('opSql').value = op.sql || '';
          this._sqlEnvId = op.envId || '';
        } else if (baseT === 'shell') {
          this.renderEnvPicker('shell', op.envId || '');
          document.getElementById('opScript').value = op.script || '';
          document.getElementById('opTimeout2').value = op.timeout || 30000;
          this._shellEnvId = op.envId || '';
        } else if (isBuiltin) {
          this.fillBuiltinForm(op);
        }
      } else {
        document.getElementById('opUrl2').value = '';
        document.getElementById('opMethod2').value = 'GET';
        document.getElementById('opContentType2').value = 'application/json';
        document.getElementById('opBody2').value = '';
        this.renderEnvPicker('sql', '');
        document.getElementById('opSql').value = '';
        this.renderEnvPicker('shell', '');
        document.getElementById('opScript').value = '';
        document.getElementById('opTimeout2').value = 30000;
        this._sqlEnvId = '';
        this._shellEnvId = '';
        this._headers = [];
        this._query = [];
      }
      this.renderKv('headers');
      this.renderKv('query');

      document.getElementById('opDrawerMask').classList.add('open');
      document.getElementById('opDrawer').classList.add('open');
      setTimeout(function () { document.getElementById('opName2').focus(); }, 120);
    },

    closeDrawer: function () {
      document.getElementById('opDrawerMask').classList.remove('open');
      document.getElementById('opDrawer').classList.remove('open');
    },

    switchForm: function (type) {
      var self = this;
      var builtin = Store.REGISTERABLE_TYPES.indexOf(type) < 0;
      document.getElementById('opFormHttp').style.display = type === 'http' ? '' : 'none';
      document.getElementById('opFormSql').style.display = type === 'sql' ? '' : 'none';
      document.getElementById('opFormShell').style.display = type === 'shell' ? '' : 'none';
      document.getElementById('opFormBuiltin').style.display = builtin ? '' : 'none';
      if (builtin) this.fillBuiltinMeta(type);
      if (type === 'sql') this.renderEnvPicker('sql', this._sqlEnvId || '');
      if (type === 'shell') this.renderEnvPicker('shell', this._shellEnvId || '');
    },

    /**
     * 渲染算子抽屉里的「环境选择器」。
     * 选择器本身由 Envs.selectHtml 生成，这里负责挂到对应容器并绑定事件：
     *   change     → 记住选择 + 刷新连接串摘要
     *   管理       → 打开环境配置弹窗（新建/编辑/删除/测试）
     *   测试连接   → 对当前选中的环境做连通性测试
     */
    renderEnvPicker: function (kind, envId) {
      var self = this;
      var boxId = kind === 'sql' ? 'opDatabaseBox' : 'opEnvBox';
      var box = document.getElementById(boxId);
      if (!box) return;

      box.innerHTML = Envs.selectHtml(kind, envId, 'envId');

      var sel = box.querySelector('[data-envselect]');
      if (sel) {
        sel.addEventListener('change', function () {
          if (kind === 'sql') self._sqlEnvId = sel.value; else self._shellEnvId = sel.value;
          /* 只重渲染这一个选择器，避免影响用户已填的 SQL / 脚本内容 */
          self.renderEnvPicker(kind, sel.value);
        });
      }

      var manageBtn = box.querySelector('[data-envmanage]');
      if (manageBtn) {
        manageBtn.addEventListener('click', function () {
          Envs.open(kind, function (env) {
            /* 「选用此配置」回调：直接回填到当前表单 */
            if (kind === 'sql') self._sqlEnvId = env.id; else self._shellEnvId = env.id;
            self.renderEnvPicker(kind, env.id);
            UI.toast('已选用环境「' + env.name + '」', 'ok');
          });
        });
      }

      var testBtn = box.querySelector('[data-envtest]');
      if (testBtn) {
        testBtn.addEventListener('click', function () {
          var cur = kind === 'sql' ? self._sqlEnvId : self._shellEnvId;
          var env = Store.getEnv(cur);
          if (!env) { UI.toast('请先选择' + (kind === 'sql' ? '数据库' : '执行环境'), 'warn'); return; }
          testBtn.disabled = true;
          testBtn.textContent = '测试中…';
          Api.env.test(env).then(function (r) {
            testBtn.disabled = false;
            testBtn.textContent = '测试连接';
            var tip = box.querySelector('[data-envsummary]');
            if (tip) {
              tip.className = 'tip env-summary ' + (r.ok ? 'test-ok' : 'test-err');
              tip.textContent = (r.ok ? '✓ 连接成功：' : '✕ 连接失败：') + r.msg
                + (r.latency ? '（' + r.latency + 'ms）' : '');
            }
            UI.toast(r.ok ? '连接成功：' + r.msg : '连接失败：' + r.msg, r.ok ? 'ok' : 'err');
          });
        });
      }
    },

    /** 内置算子：填写能力说明与配置 JSON 模板 */
    fillBuiltinMeta: function (type) {
      var meta = Store.OP_TYPES[type] || {};
      document.getElementById('opBuiltinKind').value =
        (meta.name || type) + '（' + (meta.cellType || type) + '）';
      document.getElementById('opBuiltinHint').innerHTML =
        esc(meta.desc || '') + '。按后端 DSL 的 <code>data</code> 结构填写，保存后可在编排页拖拽使用。';
    },

    /** 内置算子：把算子对象中除元信息外的字段序列化为 JSON 填入 */
    fillBuiltinForm: function (op) {
      this.fillBuiltinMeta(op.opType);
      var data = {};
      Object.keys(op).forEach(function (k) {
        if (['id', 'opType', 'name', 'description', 'createTime', 'updateTime',
          'builtin', 'group', 'scope'].indexOf(k) >= 0) return;
        data[k] = op[k];
      });
      document.getElementById('opBuiltinData').value = JSON.stringify(data, null, 2);
    },

    /** 内置算子：解析配置 JSON，返回待合并的字段；解析失败返回 null */
    parseBuiltinForm: function () {
      var raw = document.getElementById('opBuiltinData').value.trim();
      if (!raw) return {};
      try {
        var v = JSON.parse(raw);
        if (v === null || typeof v !== 'object' || Array.isArray(v)) {
          UI.toast('配置必须是 JSON 对象', 'warn');
          return null;
        }
        return v;
      } catch (e) {
        UI.toast('配置 JSON 解析失败：' + e.message, 'warn');
        return null;
      }
    },

    renderKv: function (which) {
      var self = this;
      var list = which === 'headers' ? (this._headers || []) : (this._query || []);
      var box = document.getElementById(which === 'headers' ? 'opHeadersList' : 'opQueryList');
      if (!list.length) {
        box.innerHTML = '<div class="tip" style="font-size:11px;color:var(--text-3);padding:2px 0">暂无，点击下方添加</div>';
        return;
      }
      box.innerHTML = list.map(function (it, i) {
        return '<div class="kv-row">'
          + '<input data-kv="' + which + '" data-i="' + i + '" data-f="k" placeholder="键" value="' + esc(it.k || '') + '">'
          + '<input data-kv="' + which + '" data-i="' + i + '" data-f="v" placeholder="值" value="' + esc(it.v || '') + '">'
          + '<button class="kv-del" data-kvdel="' + which + '" data-i="' + i + '">✕</button></div>';
      }).join('');

      box.querySelectorAll('[data-kv]').forEach(function (el) {
        el.addEventListener('input', function () {
          var arr = el.dataset.kv === 'headers' ? self._headers : self._query;
          arr[Number(el.dataset.i)][el.dataset.f] = el.value;
        });
      });
      box.querySelectorAll('[data-kvdel]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var arr = btn.dataset.kvdel === 'headers' ? self._headers : self._query;
          arr.splice(Number(btn.dataset.i), 1);
          self.renderKv(btn.dataset.kvdel);
        });
      });
    },

    addKv: function (which) {
      if (which === 'headers') (this._headers = this._headers || []).push({ k: '', v: '' });
      else (this._query = this._query || []).push({ k: '', v: '' });
      this.renderKv(which);
    },

    save: function () {
      var name = document.getElementById('opName2').value.trim();
      var type = document.getElementById('opType2').value;
      if (!name) { UI.toast('请输入算子名称', 'warn'); return; }

      var op = this.editingId ? Store.getOperator(this.editingId) : Store.newOperator(type);
      op.name = name;
      op.opType = type;
      op.description = document.getElementById('opDesc2').value.trim();

      /* 使用范围：内置算子保持原样，其余按单选结果写入 */
      if (!Store.isBuiltinOperator(op)) {
        var picked = document.querySelector('input[name="opScope"]:checked');
        var newScope = picked ? picked.value : 'local';
        /* 已复用的算子不允许在编辑时退回临时，避免其他实例的引用突然失效 */
        if (Store.isSharedOperator(op)) newScope = 'shared';
        op.scope = newScope;
      }

      /* 注册页下拉只提供 http/sql/shell，但编辑到的算子可能是补偿算子
         （类型带 Compensate 后缀），故统一归一化后再取字段 */
      var typeBase = Store.baseOpType(type);

      if (typeBase === 'http') {
        var url = document.getElementById('opUrl2').value.trim();
        if (!url) { UI.toast('请填写请求地址', 'warn'); return; }
        /* 字段对齐后端 node_outer_http */
        op.protocol = 'http';
        op.url = url;
        op.requestType = document.getElementById('opMethod2').value;
        op.outputs = op.outputs || { contentType: 'application/json', outputsJsonSchema: '' };
        op.outputs.contentType = document.getElementById('opContentType2').value;
        op.inputs = op.inputs || {};
        op.inputs.reqHeaders = (this._headers || []).filter(function (x) { return x.k; })
          .map(function (x) { return { name: x.k, key: x.k, type: 'Text', value: x.v }; });
        op.inputs.reqQuery = (this._query || []).filter(function (x) { return x.k; })
          .map(function (x) { return { name: x.k, key: x.k, type: 'Text', value: x.v }; });
        op.inputs.reqBodyOther = document.getElementById('opBody2').value;
        op.inputs.reqBodyType = 'json';
      } else if (typeBase === 'sql') {
        var sql = document.getElementById('opSql').value.trim();
        var dbSel = document.querySelector('#opDatabaseBox [data-envselect]');
        var envId = dbSel ? dbSel.value : (this._sqlEnvId || '');
        if (!envId) { UI.toast('请选择数据库（可在「管理」中新建）', 'warn'); return; }
        if (!sql) { UI.toast('请填写 SQL 语句', 'warn'); return; }
        var dbEnv = Store.getEnv(envId);
        op.envId = envId;
        /* 冗余保存库名，便于后端解析与无配置时只读展示 */
        op.database = dbEnv ? (dbEnv.database || dbEnv.name) : '';
        op.sql = sql;
      } else if (typeBase === 'shell') {
        var script = document.getElementById('opScript').value.trim();
        var envSel = document.querySelector('#opEnvBox [data-envselect]');
        var shEnvId = envSel ? envSel.value : (this._shellEnvId || '');
        if (!shEnvId) { UI.toast('请选择执行环境（可在「管理」中新建）', 'warn'); return; }
        if (!script) { UI.toast('请填写脚本内容', 'warn'); return; }
        var shEnv = Store.getEnv(shEnvId);
        op.envId = shEnvId;
        op.env = shEnv ? shEnv.name : '';
        op.script = script;
        op.timeout = Number(document.getElementById('opTimeout2').value) || 30000;
      } else {
        /* 内置算子：整体覆盖配置字段（保留元信息） */
        var cfg = this.parseBuiltinForm();
        if (cfg === null) return;
        Object.keys(op).forEach(function (k) {
          if (['id', 'opType', 'name', 'description', 'createTime', 'updateTime',
            'builtin', 'group', 'scope'].indexOf(k) >= 0) return;
          delete op[k];
        });
        Object.keys(cfg).forEach(function (k) { op[k] = cfg[k]; });
      }

      Api.operator.save(op).then(function () {
        Operators.closeDrawer();
        Operators.render();
        UI.toast('算子已保存，可在编排页节点库中使用', 'ok');
      });
    },

    render: function () {
      var grid = document.getElementById('opGrid');
      var empty = document.getElementById('opEmpty');
      var all = Store.listOperators();

      var list = all.filter(function (o) {
        if (!matchFilter(o, this.typeFilter)) return false;
        if (this.keyword) {
          var hay = (o.name + ' ' + (o.description || '')).toLowerCase();
          if (hay.indexOf(this.keyword) < 0) return false;
        }
        return true;
      }, this);

      document.getElementById('opCount').textContent = '共 ' + all.length + ' 个算子' +
        (list.length !== all.length ? '（筛选出 ' + list.length + ' 个）' : '');

      if (!all.length) { grid.innerHTML = ''; empty.style.display = 'flex'; return; }
      empty.style.display = 'none';

      grid.innerHTML = list.map(function (o) {
        var t = Store.OP_TYPES[o.opType] || Store.OP_TYPES.http;
        var builtin = Store.isBuiltinOperator(o);
        var shared = Store.isSharedOperator(o);
        /* 作用域标记：内置 / 复用 / 临时 */
        var tag = builtin
          ? '<span class="otag tag-builtin">内置</span>'
          : (shared ? '<span class="otag tag-shared">复用</span>'
            : '<span class="otag tag-local">临时</span>');
        /* 只有非内置且非复用的算子才需要「注册」 */
        var regBtn = (!builtin && !shared)
          ? '<button class="ic-btn primary" data-act="register">注册为复用</button>' : '';
        /* 内置算子不可删除：保留按钮位置但禁用，避免布局跳动 */
        var delBtn = builtin
          ? '<button class="ic-btn" data-act="del" disabled title="内置算子不可删除">删除</button>'
          : '<button class="ic-btn danger" data-act="del">删除</button>';
        return '<div class="op-card t-' + o.opType + '" data-id="' + o.id + '">'
          + '<div class="oc-head">'
          + '<span class="oc-ico" style="background:' + t.color + '">' + t.icon + '</span>'
          + '<span class="oc-meta">'
          + '<span class="oc-name">' + esc(o.name) + tag + '</span>'
          + '<span class="oc-type">' + esc(o.opType) + '</span>'
          + '</span></div>'
          + '<div class="oc-desc">' + esc(o.description || '暂无描述') + '</div>'
          + '<div class="oc-preview">' + esc(preview(o)) + '</div>'
          + '<div class="oc-foot">'
          + '<button class="ic-btn" data-act="edit">编辑</button>'
          + '<button class="ic-btn" data-act="detail">查看配置</button>'
          + regBtn
          + '<span class="spacer"></span>'
          + delBtn
          + '</div></div>';
      }).join('');

      grid.querySelectorAll('.op-card').forEach(function (card) {
        var id = card.dataset.id;
        card.addEventListener('click', function (e) {
          var act = e.target.dataset && e.target.dataset.act;
          if (act === 'edit') { Operators.openDrawer(id); }
          else if (act === 'register') {
            e.stopPropagation();
            var ro = Store.getOperator(id);
            UI.confirmAction(
              '将「' + ro.name + '」注册为复用算子？\n\n注册后所有实例都能看到并使用它。',
              function () {
                Api.operator.register([id]).then(function (res) {
                  if (!res.ok) { UI.toast(res.msg || '注册失败', 'warn'); return; }
                  Operators.render();
                  UI.toast('「' + ro.name + '」已注册为复用算子', 'ok');
                });
              }
            );
          }
          else if (act === 'del') {
            var o = Store.getOperator(id);
            /* 双保险：内置算子在数据层也拒绝删除 */
            if (Store.isBuiltinOperator(o)) { UI.toast('内置算子不可删除', 'warn'); return; }
            UI.confirmAction('确定删除算子「' + o.name + '」？\n若已被编排引用，相关节点将失效。', function () {
              Api.operator.remove([id]).then(function () {
                Operators.render();
                UI.toast('已删除', 'ok');
              });
            });
          } else {
            Operators.showConfig(id);
          }
        });
      });
    },

    showConfig: function (id) {
      var o = Store.getOperator(id);
      var t = Store.OP_TYPES[o.opType];
      var text = '【' + t.name + '】' + o.name + '\n\n';
      /* 补偿算子复用同族外部算子的展示逻辑 */
      var baseT = Store.baseOpType(o.opType);
      if (baseT === 'http') {
        text += (o.requestType || o.method || 'GET') + ' ' + (o.url || '') + '\n';
        text += 'Content-Type: ' + ((o.outputs && o.outputs.contentType) || 'application/json') + '\n';
        var reqHeaders = (o.inputs && o.inputs.reqHeaders) || [];
        var reqQuery = (o.inputs && o.inputs.reqQuery) || [];
        if (reqHeaders.length) {
          text += '\nHeaders:\n' + reqHeaders.map(function (h) {
            return '  ' + (h.key || '') + ': ' + (h.defaultValue || h.value || '');
          }).join('\n');
        }
        if (reqQuery.length) {
          text += '\n\nQuery:\n' + reqQuery.map(function (q) {
            return '  ' + (q.key || '') + '=' + (q.defaultValue || q.value || '');
          }).join('\n');
        }
        var body = (o.inputs && (o.inputs.reqBodyOther || o.inputs.bodyJsonSchema)) || '';
        if (body) text += '\n\nBody:\n' + body;
      } else if (baseT === 'sql') {
        var dbEnv = Store.getEnv(o.envId);
        text += '数据库：' + (dbEnv ? Store.envSummary(dbEnv) : (o.database || '—')) + '\n\nSQL:\n' + (o.sql || '');
      } else {
        var shEnv = Store.getEnv(o.envId);
        text += '执行环境：' + (shEnv ? Store.envSummary(shEnv) : (o.env || '—'))
          + '\n超时：' + (o.timeout || 30000) + ' ms\n\n脚本:\n' + (o.script || '');
      }
      UI.modal('算子配置 · ' + o.name, text, { readOnly: true, hideOk: true });
    }
  };

  /* ============================================================
     ⑤ Schema 工具
     ============================================================ */
  var SchemaTool = {
    rows: [],
    init: function () {
      var self = this;
      document.getElementById('btnAddSchemaRow').onclick = function () {
        self.rows.push({ name: '', type: 'string', required: false, description: '' });
        self.render();
      };
      document.getElementById('btnCopySchema').onclick = function () {
        UI.copy(document.getElementById('schemaOut').textContent);
      };
      this.rows = [{ name: '', type: 'string', required: false, description: '' }];
      this.render();
    },
    render: function () {
      var self = this;
      var tbody = document.querySelector('#schemaTbl tbody');
      tbody.innerHTML = this.rows.map(function (r, i) {
        return '<tr>'
          + '<td><input type="text" data-i="' + i + '" data-f="name" value="' + esc(r.name) + '" placeholder="字段名"></td>'
          + '<td><select data-i="' + i + '" data-f="type">'
          + SchemaUtil.TYPES.map(function (t) {
            return '<option' + (r.type === t ? ' selected' : '') + '>' + t + '</option>';
          }).join('') + '</select></td>'
          + '<td style="text-align:center"><input type="checkbox" data-i="' + i + '" data-f="required"' + (r.required ? ' checked' : '') + '></td>'
          + '<td><input type="text" data-i="' + i + '" data-f="description" value="' + esc(r.description) + '" placeholder="描述"></td>'
          + '<td style="text-align:center"><button class="kv-del" data-del="' + i + '">✕</button></td>'
          + '</tr>';
      }).join('');

      tbody.querySelectorAll('input,select').forEach(function (el) {
        var handler = function () {
          var i = Number(el.dataset.i), f = el.dataset.f;
          self.rows[i][f] = (el.type === 'checkbox') ? el.checked : el.value;
          self.output();
        };
        el.addEventListener('input', handler);
        el.addEventListener('change', handler);
      });
      tbody.querySelectorAll('[data-del]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          self.rows.splice(Number(btn.dataset.del), 1);
          if (!self.rows.length) self.rows.push({ name: '', type: 'string', required: false, description: '' });
          self.render();
        });
      });
      this.output();
    },
    output: function () {
      document.getElementById('schemaOut').textContent =
        JSON.stringify(SchemaUtil.buildSchema(this.rows), null, 2);
    }
  };

  /* ============================================================
     工具函数
     ============================================================ */
  function stateName(s) {
    var m = {
      RUNNING: '运行中', SUCCESS: '成功', FAIL: '失败',
      WAITE: '未运行', WAIT: '未执行', SKIP: '已跳过', SUSPEND: '已挂起'
    };
    return m[s] || s || '未运行';
  }
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

  function fmtDate(ts) {
    if (!ts) return '—';
    var d = new Date(ts);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }
  function fmtDateTime(s) {
    if (!s) return '—';
    var d = new Date(s);
    if (isNaN(d.getTime())) return String(s);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
      + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }
  function fmtTime(s) {
    var d = new Date(s);
    if (isNaN(d.getTime())) return String(s || '');
    return pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }
  function pad(n) { return n < 10 ? '0' + n : String(n); }

  function summarize(inputs) {
    if (!inputs) return '—';
    var keys = Object.keys(inputs);
    if (!keys.length) return '无入参';
    return keys.slice(0, 2).map(function (k) { return k + '=' + inputs[k]; }).join(', ')
      + (keys.length > 2 ? ' …' : '');
  }

  /** 运行结果统计 */
  function summary(r) {
    var s = { total: 0, success: 0, fail: 0, skip: 0 };
    (r.cells || []).forEach(function (c) {
      if (DslValidator.isEdge(c.cellType)) return;
      s.total++;
      var st = r.nodeStates[c.id] && r.nodeStates[c.id].state;
      if (st === 'SUCCESS') s.success++;
      else if (st === 'FAIL') s.fail++;
      else if (st === 'SKIP') s.skip++;
    });
    return s;
  }

  function preview(o) {
    /* 补偿算子与同族外部算子的摘要形式一致 */
    var t = Store.baseOpType(o.opType);
    if (t === 'http') return (o.requestType || o.method || 'GET') + ' ' + (o.url || '—');
    if (t === 'sql') return (o.database || '—') + ' · ' + (o.sql || '').split('\n')[0].slice(0, 46);
    if (t === 'shell') return (o.env || '—') + ' · ' + (o.script || '').split('\n')[0].slice(0, 46);
    /* 内置算子：展示关键配置摘要 */
    var keys = Object.keys(o).filter(function (k) {
      return ['id', 'opType', 'name', 'description', 'createTime', 'updateTime',
        'builtin', 'group'].indexOf(k) < 0;
    });
    if (!keys.length) return '无额外配置';
    return keys.slice(0, 3).map(function (k) {
      return k + '=' + preview1(o[k]);
    }).join(', ') + (keys.length > 3 ? ' …' : '');
  }

  function preview1(v) {
    if (v === null || v === undefined) return '—';
    if (Array.isArray(v)) return '[' + v.length + ']';
    if (typeof v === 'object') return '{…}';
    var s = String(v);
    return s.length > 28 ? s.slice(0, 28) + '…' : s;
  }

  /**
   * 算子类型筛选。
   * 支持三种取值：
   *   ''            全部
   *   '__inner'     内部算子（group=inner）
   *   '__compensate' 补偿算子（group=compensate）
   *   其他           按 opType 精确匹配（http / sql / shell / 具体内置类型）
   */
  function matchFilter(o, filter) {
    if (!filter) return true;
    var meta = Store.OP_TYPES[o.opType] || {};
    var grp = o.group || meta.group || o.opType;
    if (filter === '__inner') return grp === 'inner';
    if (filter === '__compensate') return grp === 'compensate';
    return o.opType === filter;
  }

  function pretty(v) {
    if (v === null || v === undefined) return '—';
    if (typeof v === 'string') return v;
    try { return JSON.stringify(v, null, 2); } catch (e) { return String(v); }
  }

  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  global.Instances = Instances;
  global.Runs = Runs;
  global.RunDetail = RunDetail;
  global.Envs = Envs;
  global.Operators = Operators;
  global.SchemaTool = SchemaTool;
})(window);
