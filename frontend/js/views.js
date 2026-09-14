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
      UI.modal('新建实例', '', {
        onOk: function (val) {
          var name = (val || '').trim();
          if (!name) { UI.toast('请输入实例名称', 'warn'); return false; }
          var inst = Store.createInstance(name, '');
          self.render();
          UI.toast('实例已创建，正在进入编排…', 'ok');
          setTimeout(function () { App.openDesigner(inst.id); }, 260);
        }
      });
      var ta = document.getElementById('modalText');
      ta.readOnly = false;
      ta.placeholder = '请输入实例名称，例如：用户注册流程';
      ta.style.height = '44px';
      setTimeout(function () {
        ta.focus();
        ta.addEventListener('keydown', function onKey(e) {
          if (e.key === 'Enter') { e.preventDefault(); document.getElementById('modalOk').click(); }
        });
      }, 60);
      document.getElementById('modalMsg').textContent = '实例创建后可在编排页设计流程，并可多次运行以生成运行记录。';
      document.getElementById('modalMsg').className = 'modal-msg';
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
     ④ 算子注册（http / sql / shell）
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

      document.getElementById('opDrawerTitle').textContent = op ? '编辑算子' : '新建算子';
      document.getElementById('opName2').value = op ? op.name : '';
      document.getElementById('opDesc2').value = op ? (op.description || '') : '';
      document.getElementById('opType2').value = op ? op.opType : 'http';
      document.getElementById('opType2').disabled = !!op;   // 编辑时不允许改类型

      this.switchForm(op ? op.opType : 'http');

      if (op) {
        if (op.opType === 'http') {
          document.getElementById('opUrl2').value = op.url || '';
          document.getElementById('opMethod2').value = op.method || 'GET';
          document.getElementById('opContentType2').value = op.contentType || 'application/json';
          document.getElementById('opBody2').value = op.body || '';
          this._headers = (op.headers || []).slice();
          this._query = (op.query || []).slice();
        } else if (op.opType === 'sql') {
          document.getElementById('opDatabase').value = op.database || '';
          document.getElementById('opSql').value = op.sql || '';
        } else {
          document.getElementById('opEnv').value = op.env || '';
          document.getElementById('opScript').value = op.script || '';
          document.getElementById('opTimeout2').value = op.timeout || 30000;
        }
      } else {
        document.getElementById('opUrl2').value = '';
        document.getElementById('opMethod2').value = 'GET';
        document.getElementById('opContentType2').value = 'application/json';
        document.getElementById('opBody2').value = '';
        document.getElementById('opDatabase').value = '';
        document.getElementById('opSql').value = '';
        document.getElementById('opEnv').value = '';
        document.getElementById('opScript').value = '';
        document.getElementById('opTimeout2').value = 30000;
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
      document.getElementById('opFormHttp').style.display = type === 'http' ? '' : 'none';
      document.getElementById('opFormSql').style.display = type === 'sql' ? '' : 'none';
      document.getElementById('opFormShell').style.display = type === 'shell' ? '' : 'none';
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

      if (type === 'http') {
        var url = document.getElementById('opUrl2').value.trim();
        if (!url) { UI.toast('请填写请求地址', 'warn'); return; }
        op.url = url;
        op.method = document.getElementById('opMethod2').value;
        op.contentType = document.getElementById('opContentType2').value;
        op.body = document.getElementById('opBody2').value;
        op.headers = (this._headers || []).filter(function (x) { return x.k; });
        op.query = (this._query || []).filter(function (x) { return x.k; });
      } else if (type === 'sql') {
        var db = document.getElementById('opDatabase').value;
        var sql = document.getElementById('opSql').value.trim();
        if (!db) { UI.toast('请选择数据库', 'warn'); return; }
        if (!sql) { UI.toast('请填写 SQL 语句', 'warn'); return; }
        op.database = db;
        op.sql = sql;
      } else {
        var env = document.getElementById('opEnv').value;
        var script = document.getElementById('opScript').value.trim();
        if (!env) { UI.toast('请选择执行环境', 'warn'); return; }
        if (!script) { UI.toast('请填写脚本内容', 'warn'); return; }
        op.env = env;
        op.script = script;
        op.timeout = Number(document.getElementById('opTimeout2').value) || 30000;
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
        if (this.typeFilter && o.opType !== this.typeFilter) return false;
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
        return '<div class="op-card t-' + o.opType + '" data-id="' + o.id + '">'
          + '<div class="oc-head">'
          + '<span class="oc-ico" style="background:' + t.color + '">' + t.icon + '</span>'
          + '<span class="oc-meta">'
          + '<span class="oc-name">' + esc(o.name) + '</span>'
          + '<span class="oc-type">' + esc(o.opType) + '</span>'
          + '</span></div>'
          + '<div class="oc-desc">' + esc(o.description || '暂无描述') + '</div>'
          + '<div class="oc-preview">' + esc(preview(o)) + '</div>'
          + '<div class="oc-foot">'
          + '<button class="ic-btn" data-act="edit">编辑</button>'
          + '<button class="ic-btn" data-act="detail">查看配置</button>'
          + '<span class="spacer"></span>'
          + '<button class="ic-btn danger" data-act="del">删除</button>'
          + '</div></div>';
      }).join('');

      grid.querySelectorAll('.op-card').forEach(function (card) {
        var id = card.dataset.id;
        card.addEventListener('click', function (e) {
          var act = e.target.dataset && e.target.dataset.act;
          if (act === 'edit') { Operators.openDrawer(id); }
          else if (act === 'del') {
            var o = Store.getOperator(id);
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
      if (o.opType === 'http') {
        text += o.method + ' ' + o.url + '\n';
        text += 'Content-Type: ' + o.contentType + '\n';
        if ((o.headers || []).length) text += '\nHeaders:\n' + o.headers.map(function (h) { return '  ' + h.k + ': ' + h.v; }).join('\n');
        if ((o.query || []).length) text += '\n\nQuery:\n' + o.query.map(function (q) { return '  ' + q.k + '=' + q.v; }).join('\n');
        if (o.body) text += '\n\nBody:\n' + o.body;
      } else if (o.opType === 'sql') {
        text += '数据库：' + o.database + '\n\nSQL:\n' + o.sql;
      } else {
        text += '执行环境：' + o.env + '\n超时：' + o.timeout + ' ms\n\n脚本:\n' + o.script;
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
    if (o.opType === 'http') return (o.method || 'GET') + ' ' + (o.url || '—');
    if (o.opType === 'sql') return (o.database || '—') + ' · ' + (o.sql || '').split('\n')[0].slice(0, 46);
    return (o.env || '—') + ' · ' + (o.script || '').split('\n')[0].slice(0, 46);
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
  global.Operators = Operators;
  global.SchemaTool = SchemaTool;
})(window);
