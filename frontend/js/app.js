/* ===== 应用入口 =====
 * 职责：UI 工具（toast / modal / 抽屉）、视图路由与面包屑、实例运行引擎、编排页工具栏
 *
 * 视图：
 *   instances    实例列表
 *   designer     编排页（需 instId）
 *   runs         运行记录（需 instId）
 *   run-detail   运行详情（需 instId + runId）
 *   operator     算子注册
 *   schema       Schema 工具
 */
(function (global) {
  'use strict';

  /* ============================================================
     UI 工具
     ============================================================ */
  var UI = {
    toast: function (msg, type) {
      var box = document.getElementById('toastWrap');
      var el = document.createElement('div');
      el.className = 'toast' + (type ? ' ' + type : '');
      el.textContent = msg;
      box.appendChild(el);
      setTimeout(function () {
        el.style.transition = 'opacity .25s,transform .25s';
        el.style.opacity = '0';
        el.style.transform = 'translateX(20px)';
        setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 260);
      }, type === 'err' ? 4200 : 2400);
    },

    modal: function (title, initialText, opts) {
      opts = opts || {};
      var mask = document.getElementById('modalMask');
      var ta = document.getElementById('modalText');
      var msg = document.getElementById('modalMsg');
      var okBtn = document.getElementById('modalOk');
      var cancelBtn = document.getElementById('modalCancel');
      var closeBtn = document.getElementById('modalClose');

      document.getElementById('modalTitle').textContent = title;
      ta.value = initialText || '';
      ta.readOnly = !!opts.readOnly;
      ta.style.height = opts.inputHeight || '';
      ta.placeholder = opts.placeholder || '';
      msg.textContent = opts.msg || '';
      msg.className = 'modal-msg ' + (opts.msgType || '');
      mask.classList.add('open');
      setTimeout(function () { if (!opts.readOnly) ta.focus(); }, 60);

      function cleanup() {
        mask.classList.remove('open');
        okBtn.onclick = null; cancelBtn.onclick = null; closeBtn.onclick = null; mask.onclick = null;
        ta.placeholder = '';
      }
      cancelBtn.onclick = cleanup;
      closeBtn.onclick = cleanup;
      okBtn.style.display = opts.hideOk ? 'none' : '';
      okBtn.textContent = opts.okText || '确定';
      okBtn.onclick = function () {
        if (opts.onOk && opts.onOk(ta.value) === false) return;
        cleanup();
      };
      mask.onclick = function (e) { if (e.target === mask) cleanup(); };
      return { close: cleanup, textarea: ta, msg: msg };
    },

    /**
     * 轻量单行输入框。
     * 用于「新建实例」「重命名」等只需一个名称的场景，
     * 避免复用 modal() 的大 textarea（原先靠 height:44px 硬凑单行输入）。
     * @param {Object} opts { title, hint, value, placeholder, okText,
     *                        cancelText, required, maxLength, onOk(value) }
     *                       onOk 返回 false 时不关闭
     */
    prompt: function (opts) {
      opts = opts || {};
      var mask = document.getElementById('promptMask');
      var titleEl = document.getElementById('promptTitle');
      var hintEl = document.getElementById('promptHint');
      var input = document.getElementById('promptInput');
      var okBtn = document.getElementById('promptOk');
      var cancelBtn = document.getElementById('promptCancel');
      var closeBtn = document.getElementById('promptClose');

      titleEl.textContent = opts.title || '输入';
      hintEl.textContent = opts.hint || '';
      input.value = opts.value || '';
      input.placeholder = opts.placeholder || '';
      if (opts.maxLength) input.maxLength = opts.maxLength; else input.removeAttribute('maxlength');
      okBtn.textContent = opts.okText || '确定';
      cancelBtn.textContent = opts.cancelText || '取消';

      mask.classList.add('open');
      setTimeout(function () { input.focus(); input.select(); }, 60);

      function cleanup() {
        mask.classList.remove('open');
        okBtn.onclick = null; cancelBtn.onclick = null; closeBtn.onclick = null; mask.onclick = null;
        input.onkeydown = null;
        input.value = '';
      }
      function submit() {
        var v = input.value;
        if (opts.required && !v.trim()) {
          UI.toast(opts.requiredMsg || '不能为空', 'warn');
          input.focus();
          return;
        }
        if (opts.onOk && opts.onOk(v) === false) return;
        cleanup();
      }

      okBtn.onclick = submit;
      cancelBtn.onclick = cleanup;
      closeBtn.onclick = cleanup;
      mask.onclick = function (e) { if (e.target === mask) cleanup(); };
      input.onkeydown = function (e) {
        if (e.key === 'Enter') { e.preventDefault(); submit(); }
        if (e.key === 'Escape') { e.preventDefault(); cleanup(); }
      };
      return { close: cleanup, input: input };
    },

    /**
     * 轻量确认框：一句话提示 + 确定/取消。
     * 不复用 modal()（那是为大文本编辑设计的，780px 宽 + 大 textarea）。
     * @param {string} text   提示内容（支持换行）
     * @param {Function} onYes 点「确定」时回调
     * @param {Object} [opts] { title, okText, cancelText, danger }
     */
    confirmAction: function (text, onYes, opts) {
      opts = opts || {};
      var mask = document.getElementById('confirmMask');
      var titleEl = document.getElementById('confirmTitle');
      var textEl = document.getElementById('confirmText');
      var okBtn = document.getElementById('confirmOk');
      var cancelBtn = document.getElementById('confirmCancel');
      var closeBtn = document.getElementById('confirmClose');

      titleEl.textContent = opts.title || '确认操作';
      textEl.textContent = text || '';
      okBtn.textContent = opts.okText || '确定';
      cancelBtn.textContent = opts.cancelText || '取消';
      okBtn.classList.toggle('btn-danger', !!opts.danger);

      mask.classList.add('open');
      setTimeout(function () { okBtn.focus(); }, 60);

      function cleanup() {
        mask.classList.remove('open');
        okBtn.onclick = null; cancelBtn.onclick = null; closeBtn.onclick = null; mask.onclick = null;
        document.removeEventListener('keydown', onKey, true);
      }
      function onKey(e) {
        if (!mask.classList.contains('open')) return;
        if (e.key === 'Escape') { e.preventDefault(); cleanup(); }
        if (e.key === 'Enter') { e.preventDefault(); okBtn.click(); }
      }

      cancelBtn.onclick = cleanup;
      closeBtn.onclick = cleanup;
      okBtn.onclick = function () { cleanup(); if (onYes) onYes(); };
      mask.onclick = function (e) { if (e.target === mask) cleanup(); };
      document.addEventListener('keydown', onKey, true);
      return { close: cleanup };
    },

    copy: function (text) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(
          function () { UI.toast('已复制到剪贴板', 'ok'); },
          function () { UI.toast('复制失败，请手动复制', 'err'); }
        );
      } else {
        var ta = document.createElement('textarea');
        ta.value = text; document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); UI.toast('已复制到剪贴板', 'ok'); }
        catch (e) { UI.toast('复制失败，请手动复制', 'err'); }
        document.body.removeChild(ta);
      }
    }
  };
  global.UI = UI;

  /* ============================================================
     应用路由
     ============================================================ */
  var App = {
    current: 'instances',

    /** 切换视图；instId/runId 用于面包屑与页面数据 */
    show: function (view, ctx) {
      ctx = ctx || {};
      this.current = view;

      document.querySelectorAll('.view').forEach(function (v) {
        v.classList.toggle('active', v.id === 'view-' + view);
      });

      /* 主导航高亮：编排/运行相关视图都归属「实例列表」 */
      var navKey = (view === 'designer' || view === 'runs' || view === 'run-detail')
        ? 'instances' : view;
      document.querySelectorAll('.nav-item').forEach(function (n) {
        n.classList.toggle('active', n.dataset.view === navKey);
      });

      this.renderCrumb(view, ctx);

      if (view === 'instances') Instances.render();
      if (view === 'operator') Operators.render();
      if (view === 'designer') setTimeout(function () { Designer.fit(); }, 40);
      if (view === 'runs') Runs.render();
    },

    /** 面包屑 */
    renderCrumb: function (view, ctx) {
      var box = document.getElementById('crumb');
      var inst = ctx.instId ? Store.getInstance(ctx.instId) : null;
      var html = '';

      if (view === 'designer' && inst) {
        html = '<span class="crumb-item" data-go="instances">实例列表</span>'
          + '<span class="crumb-sep">/</span>'
          + '<span class="crumb-cur">' + esc(inst.name) + ' · 编排</span>';
      } else if (view === 'runs' && inst) {
        html = '<span class="crumb-item" data-go="instances">实例列表</span>'
          + '<span class="crumb-sep">/</span>'
          + '<span class="crumb-item" data-go-design="' + inst.id + '">' + esc(inst.name) + '</span>'
          + '<span class="crumb-sep">/</span>'
          + '<span class="crumb-cur">运行记录</span>';
      } else if (view === 'run-detail' && inst && ctx.run) {
        html = '<span class="crumb-item" data-go="instances">实例列表</span>'
          + '<span class="crumb-sep">/</span>'
          + '<span class="crumb-item" data-go-runs="' + inst.id + '">' + esc(inst.name) + ' · 运行记录</span>'
          + '<span class="crumb-sep">/</span>'
          + '<span class="crumb-cur">第 ' + ctx.run.seq + ' 次运行</span>';
      }

      box.innerHTML = html;
      box.classList.toggle('show', !!html);

      box.querySelectorAll('[data-go]').forEach(function (el) {
        el.onclick = function () { App.show(el.dataset.go); };
      });
      box.querySelectorAll('[data-go-design]').forEach(function (el) {
        el.onclick = function () { App.openDesigner(el.dataset.goDesign); };
      });
      box.querySelectorAll('[data-go-runs]').forEach(function (el) {
        el.onclick = function () { App.openRuns(el.dataset.goRuns); };
      });
    },

    /* ==================== 实例相关跳转 ==================== */

    /** 打开某实例的编排页 */
    openDesigner: function (instId) {
      var inst = Store.getInstance(instId);
      if (!inst) { UI.toast('实例不存在', 'err'); return; }
      Store.setCurrentInstanceId(instId);
      Designer.loadInstance(inst);
      this.show('designer', { instId: instId });
      /* 进入编排页时收起属性面板，把视野留给画布；
         用户双击节点或拖入新节点时再展开。 */
      Designer.collapseInspector();
    },

    /** 打开某实例的运行记录 */
    openRuns: function (instId) {
      Store.setCurrentInstanceId(instId);
      Runs.open(instId);
      this.show('runs', { instId: instId });
    },

    /** 打开某个运行详情 */
    openRunDetail: function (instId, runId) {
      var run = Store.getRun(instId, runId);
      if (!run) { UI.toast('运行记录不存在', 'err'); return; }
      RunDetail.open(instId, runId);
      this.show('run-detail', { instId: instId, run: run });
    },

    /* ==================== 运行引擎 ==================== */

    /**
     * 运行实例：先生成一条运行记录，再模拟逐节点推进。
     * 后端接口就绪后，把此处的模拟逻辑替换为 Api.instance.run 即可。
     */
    runInstance: function (instId) {
      var self = this;
      var inst = Store.getInstance(instId);
      if (!inst) { UI.toast('实例不存在', 'err'); return; }

      var dsl = Store.toDSL(inst);
      var v = DslValidator.validate(dsl);
      if (!v.ok) {
        UI.modal('无法运行', v.errors.join('\n'), {
          readOnly: true, hideOk: true,
          msg: '编排存在 ' + v.errors.length + ' 个错误，请先修正',
          msgType: 'err'
        });
        return;
      }

      /* 收集入参（按开始节点 Schema） */
      var typeMap = SchemaUtil.getStartParamsTypeMap(dsl);
      var keys = Object.keys(typeMap);
      if (keys.length) {
        self.askParams(keys, typeMap, function (inputs) {
          self.startRun(inst, inputs);
        });
      } else {
        self.startRun(inst, {});
      }
    },

    /** 弹出入参填写框 */
    askParams: function (keys, typeMap, onDone) {
      var rows = keys.map(function (k) {
        var t = typeMap[k];
        if (t === 'boolean') {
          return '<div class="form-row"><label>' + esc(k) + '</label>'
            + '<select data-p="' + esc(k) + '"><option value="false">false</option><option value="true">true</option></select></div>';
        }
        if (t === 'object' || t === 'array') {
          return '<div class="form-row"><label>' + esc(k) + '</label>'
            + '<textarea rows="2" data-p="' + esc(k) + '" placeholder="JSON"></textarea></div>';
        }
        return '<div class="form-row"><label>' + esc(k) + '</label>'
          + '<input data-p="' + esc(k) + '" placeholder="' + t + '"></div>';
      }).join('');

      UI.modal('运行入参', '', {
        readOnly: true, hideOk: false, okText: '开始运行',
        onOk: function () {
          var inputs = {};
          document.querySelectorAll('#modalMsg [data-p]').forEach(function (el) {
            var k = el.dataset.p;
            if (el.value === '') return;
            inputs[k] = SchemaUtil.castValue(el.value, typeMap[k] || 'string');
          });
          onDone(inputs);
        }
      });
      var msg = document.getElementById('modalMsg');
      msg.className = 'modal-msg';
      msg.innerHTML = '<div style="margin-bottom:10px;color:var(--text-2);white-space:normal">'
        + '按开始节点声明的 Schema 填写本次运行入参：</div>' + rows;
    },

    /** 创建运行记录并逐节点推进 */
    startRun: function (inst, inputs) {
      var self = this;
      var run = Store.createRun(inst.id, {
        inputs: inputs,
        cells: JSON.parse(JSON.stringify(inst.cells || [])),
        groups: JSON.parse(JSON.stringify(inst.groups || []))
      });

      /* 实例状态置为运行中 */
      inst.lastRunState = 'RUNNING';
      inst.runCount = (inst.runCount || 0) + 1;
      Store.saveInstance(inst);

      self.addLog(run, 'INFO', '', '实例「' + inst.name + '」开始运行（第 ' + run.seq + ' 次）');
      Store.saveRun(run);

      UI.toast('已开始运行，正在执行…', 'ok');

      var order = self.planOrder(run);
      var idx = 0;
      var startedAt = Date.now();

      function step() {
        if (idx >= order.length) return finish();
        var cell = order[idx];
        var nodeName = cell.name || cell.cellType;

        run.nodeStates[cell.id] = {
          state: 'RUNNING', startTime: new Date().toISOString(),
          inputs: self.mockInputs(cell, inputs), duration: 0
        };
        Store.saveRun(run);

        self.addLog(run, 'INFO', nodeName, '开始执行：' + nodeName);

        var cost = 260 + Math.floor(Math.random() * 620);
        setTimeout(function () {
          var ok = self.mockSuccess(cell);
          var st = run.nodeStates[cell.id];
          st.state = ok ? 'SUCCESS' : 'FAIL';
          st.endTime = new Date().toISOString();
          st.duration = cost;
          st.outputs = ok ? self.mockOutputs(cell) : null;
          if (!ok) st.error = self.mockError(cell);
          Store.saveRun(run);

          if (ok) {
            self.addLog(run, 'INFO', nodeName, nodeName + ' 执行成功，耗时 ' + cost + ' ms');
          } else {
            self.addLog(run, 'ERRO', nodeName, nodeName + ' 执行失败：' + st.error);
          }
          self.addNodeLog(run, cell.id, ok ? 'INFO' : 'ERRO',
            ok ? '执行成功，耗时 ' + cost + ' ms' : '执行失败：' + st.error);

          /* 失败即终止（后端为触发补偿分支） */
          if (!ok) return finish(true);

          idx++;
          step();
        }, cost);
      }

      function finish(failed) {
        /* 未执行的节点标记为跳过 */
        order.forEach(function (c, i) {
          if (!run.nodeStates[c.id]) {
            run.nodeStates[c.id] = { state: 'WAIT', duration: 0 };
          }
        });
        if (failed) {
          run.state = 'FAIL';
          self.addLog(run, 'ERRO', '', '实例运行失败');
        } else {
          run.state = 'SUCCESS';
          self.addLog(run, 'INFO', '', '实例运行成功');
        }
        run.endTime = new Date().toISOString();
        run.duration = Date.now() - startedAt;
        Store.saveRun(run);

        inst.lastRunState = run.state;
        Store.saveInstance(inst);

        UI.toast('运行结束：' + (failed ? '失败' : '成功') + '（耗时 ' + run.duration + ' ms）',
          failed ? 'err' : 'ok');

        if (self.current === 'runs') Runs.render();
        if (self.current === 'instances') Instances.render();
        if (self.current === 'run-detail') RunDetail.open(inst.id, run.id);
      }

      /* 跳转到运行记录页 */
      this.openRuns(inst.id);
      setTimeout(function () { Runs.render(); }, 30);
    },

    /* ---------- 模拟执行辅助（后端接口就绪后整体替换） ---------- */

    /** 按拓扑顺序展开节点（忽略连线） */
    planOrder: function (run) {
      var cells = run.cells || [];
      var nodes = cells.filter(function (c) { return !DslValidator.isEdge(c.cellType); });
      var edges = cells.filter(function (c) { return DslValidator.isEdge(c.cellType); });

      var indeg = {}, children = {};
      nodes.forEach(function (n) { indeg[n.id] = 0; children[n.id] = []; });
      edges.forEach(function (e) {
        var s = e.source && e.source.cell, t = e.target && e.target.cell;
        if (indeg[t] !== undefined) indeg[t]++;
        if (children[s]) children[s].push(t);
      });

      var level = {}, queue = [];
      nodes.forEach(function (n) { if (indeg[n.id] === 0) { level[n.id] = 0; queue.push(n.id); } });
      var guard = 0;
      while (queue.length && guard++ < 500) {
        var cur = queue.shift();
        (children[cur] || []).forEach(function (c) {
          var lv = (level[cur] || 0) + 1;
          if (level[c] === undefined || lv > level[c]) level[c] = lv;
          indeg[c]--;
          if (indeg[c] <= 0) queue.push(c);
        });
      }
      return nodes.slice().sort(function (a, b) {
        var la = level[a.id] === undefined ? 99 : level[a.id];
        var lb = level[b.id] === undefined ? 99 : level[b.id];
        if (la !== lb) return la - lb;
        return String(a.id).localeCompare(String(b.id));
      });
    },

    mockInputs: function (cell, runInputs) {
      var d = cell.data || {};
      if (cell.cellType === 'node_start') return runInputs;
      /* 补偿算子复用同族外部算子的入参形态 */
      var ot = d.opType ? Store.baseOpType(d.opType) : '';
      if (cell.cellType === 'node_op' && ot === 'sql') {
        return { database: d.database || '—', sql: '（按算子注册定义）' };
      }
      if (cell.cellType === 'node_op' && ot === 'shell') {
        return { env: d.env || '—', script: '（按算子注册定义）' };
      }
      if (cell.cellType === 'node_op') {
        return { method: d.requestType || d.method || 'POST', url: d.url || '—', body: d.body || '' };
      }
      if (cell.cellType === 'node_outer_http') {
        return { method: d.method || 'POST', url: d.url || '—', body: d.body || '' };
      }
      if (cell.cellType === 'node_inner_sleep') return { milliseconds: d.milliseconds || 1000 };
      if (cell.cellType === 'node_end') return { contentType: d.contentType || 'application/json' };
      return {};
    },

    mockOutputs: function (cell) {
      if (cell.cellType === 'node_start') return { accepted: true };
      if (cell.cellType === 'node_end') return { ok: true };
      var ot = (cell.data && cell.data.opType) ? Store.baseOpType(cell.data.opType) : '';
      if (cell.cellType === 'node_op' && ot === 'sql') {
        return { rows: 3, data: [{ id: 1 }, { id: 2 }, { id: 3 }] };
      }
      if (cell.cellType === 'node_op' && ot === 'shell') {
        return { exitCode: 0, stdout: 'done' };
      }
      return { code: 200, message: 'success' };
    },

    mockSuccess: function (cell) {
      /* 让「校验类」节点有较大概率失败，便于观察失败态与日志 */
      var d = cell.data || {};
      if (d.mockFail === true) return false;
      return Math.random() > 0.12;
    },

    mockError: function () {
      var errs = [
        'Connection refused: connect timed out',
        'HTTP 500 Internal Server Error',
        'SQLSyntaxErrorException: Unknown column',
        'Permission denied (exit code 1)'
      ];
      return errs[Math.floor(Math.random() * errs.length)];
    },

    addLog: function (run, level, nodeName, message) {
      run.logs.push({ time: new Date().toISOString(), level: level, nodeName: nodeName, message: message });
    },

    addNodeLog: function (run, nodeId, level, message) {
      if (!run.nodeLogs[nodeId]) run.nodeLogs[nodeId] = [];
      run.nodeLogs[nodeId].push({ time: new Date().toISOString(), level: level, message: message });
    }
  };
  global.App = App;

  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /* ============================================================
     接口设置抽屉
     ============================================================ */
  function initApiDrawer() {
    var mask = document.getElementById('apiDrawerMask');
    var drawer = document.getElementById('apiDrawer');

    function open() {
      document.getElementById('apiBase').value = Store.getSettings().apiBase || '';
      mask.classList.add('open'); drawer.classList.add('open');
    }
    function close() { mask.classList.remove('open'); drawer.classList.remove('open'); }

    document.getElementById('apiConfigBtn').onclick = open;
    document.getElementById('apiDrawerClose').onclick = close;
    mask.onclick = close;

    document.getElementById('btnApiSave').onclick = function () {
      var s = Store.getSettings();
      s.apiBase = document.getElementById('apiBase').value.trim();
      Store.setSettings(s);
      UI.toast('接口地址已保存', 'ok');
      close();
      checkApi();
    };
    document.getElementById('btnApiTest').onclick = function () {
      var s = Store.getSettings();
      s.apiBase = document.getElementById('apiBase').value.trim();
      Store.setSettings(s);
      UI.toast('正在检测…');
      checkApi(true);
    };
  }

  function checkApi(verbose) {
    var box = document.getElementById('apiStatus');
    var text = document.getElementById('apiStatusText');
    Api.ping().then(function (res) {
      text.textContent = '已连通 · ' + res.status;
      box.className = 'api-status ok';
      if (verbose) UI.toast('后端连通正常（HTTP ' + res.status + '）', 'ok');
    }).catch(function (err) {
      text.textContent = '不可达';
      box.className = 'api-status fail';
      if (verbose) UI.toast('后端不可达：' + err.message + '（本地数据仍可正常使用）', 'err');
    });
  }

  /* ============================================================
     编排页工具栏
     ============================================================ */
  function initDesignerToolbar() {
    var okBtn = document.getElementById('modalOk');

    /* 返回实例列表 */
    document.getElementById('btnBackInstances').onclick = function () {
      Designer.persist();
      App.show('instances');
    };

    /* 导入 */
    document.getElementById('btnImport').onclick = function () {
      UI.modal('导入 DSL', '', {
        okText: '导入',
        msg: '粘贴符合《服务编排 DSL 规范》的 JSON，将覆盖当前画布',
        onOk: function (val) {
          try {
            Designer.importDSL(val);
            UI.toast('导入成功，请记得保存', 'ok');
          } catch (e) {
            var m = document.getElementById('modalMsg');
            m.textContent = '导入失败：' + e.message;
            m.className = 'modal-msg err';
            return false;
          }
        }
      });
    };

    /* 导出 */
    document.getElementById('btnExport').onclick = function () {
      var out = Designer.exportDSL();
      var text = JSON.stringify(out.dsl, null, 2);
      var v = out.validation;
      var msg = v.ok
        ? ('✔ 校验通过' + (v.warnings.length ? '（' + v.warnings.length + ' 条提示）\n' + v.warnings.join('\n') : ''))
        : ('✘ 校验未通过：\n' + v.errors.join('\n'));
      UI.modal('导出 DSL（可直接交给后端运行）', text, {
        readOnly: true, msg: msg, msgType: v.ok ? 'ok' : 'err',
        okText: '复制',
        onOk: function () { UI.copy(text); }
      });
    };

    /* 校验 */
    document.getElementById('btnValidate').onclick = function () {
      var out = Designer.exportDSL();
      var v = out.validation;
      var lines = [v.ok ? '✔ 校验通过' : '✘ 发现 ' + v.errors.length + ' 个错误'];
      if (v.errors.length) lines.push('', '【错误】', v.errors.join('\n'));
      if (v.warnings.length) lines.push('', '【提示】', v.warnings.join('\n'));
      UI.modal('编排校验结果', lines.join('\n'), {
        readOnly: true, hideOk: true,
        msg: v.ok ? '可以保存并运行' : '请修正错误后再运行',
        msgType: v.ok ? 'ok' : 'err'
      });
    };

    /* 保存 */
    document.getElementById('btnSaveDesign').onclick = function () {
      var instId = Designer.instId;
      if (!instId) { UI.toast('未关联实例', 'err'); return; }
      var inst = Store.getInstance(instId);
      var dsl = Store.toDSL(Designer.model);
      inst.cells = dsl.cells;
      inst.groups = dsl.groups;
      Store.saveInstance(inst);
      App.show('instances');
      UI.toast('编排已保存', 'ok');
    };

    /* 保存并运行 */
    document.getElementById('btnRun').onclick = function () {
      var instId = Designer.instId;
      if (!instId) { UI.toast('未关联实例', 'err'); return; }
      var out = Designer.exportDSL();
      if (!out.validation.ok) {
        UI.modal('无法运行', out.validation.errors.join('\n'), {
          readOnly: true, hideOk: true,
          msg: '请先修正校验错误', msgType: 'err'
        });
        return;
      }
      /* 先保存编排 */
      var inst = Store.getInstance(instId);
      inst.cells = out.dsl.cells;
      inst.groups = out.dsl.groups;
      Store.saveInstance(inst);

      App.runInstance(instId);
    };
  }

  /* ============================================================
     启动
     ============================================================ */
  document.addEventListener('DOMContentLoaded', function () {
    /* 主导航 */
    document.querySelectorAll('.nav-item').forEach(function (t) {
      t.onclick = function () { App.show(t.dataset.view); };
    });
    document.getElementById('brandHome').onclick = function () { App.show('instances'); };

    /* 预置内置算子与补偿算子，并在版本升级时清理已移除的孤儿记录 */
    Store.seedBuiltinOperators();

    /* 预置环境配置示例（Shell 执行环境 / 数据库连接），仅在首次运行时执行 */
    Store.seedEnvs();

    /* 模块初始化 */
    Designer.init();
    Instances.init();
    Runs.init();
    Operators.init();
    SchemaTool.init();
    initApiDrawer();
    initDesignerToolbar();

    /* 首次进入：默认实例列表 */
    App.show('instances');
    checkApi();

    /* 没有实例时给出一次引导 */
    if (!Store.listInstances().length) {
      setTimeout(function () {
        UI.toast('首次使用：请先「新建实例」，再进入编排设计流程', 'ok');
      }, 600);
    }
  });
})(window);
