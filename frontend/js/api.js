/* ===== 后端接口层 =====
 * 职责：统一封装与后端的交互。
 *
 * 策略：
 *   - 已实现的后端接口（/inst/run/or/getResult、/registerOperator/*）直接调用；
 *   - 尚无后端接口的能力（实例 CRUD、运行记录、日志查询）按「建议契约」写好方法，
 *     当前走本地 Store 兜底；后端就绪后把 REMOTE 开关对应的分支打开即可切换。
 *
 * 建议契约（供后端补齐时参考，见 docs/05-REST接口说明.md §8）：
 *   POST /instance/save           保存实例（含编排）
 *   POST /instance/delete         批量删除实例
 *   GET  /instance/list           实例列表
 *   GET  /instance/findById       实例详情
 *   POST /instance/run            运行实例（返回 runId）
 *   GET  /run/list?instId=        运行记录列表
 *   GET  /run/findById            运行记录详情（含编排快照与节点状态）
 *   GET  /log/list?runId=         实例级日志
 *   GET  /log/nodeLogs?runId=&nodeId=  算子级日志
 *   POST /operator/save           注册算子（http / sql / shell）
 *   GET  /operator/list           算子列表
 *   GET  /operator/findById       算子详情
 *   POST /operator/delete         批量删除算子
 */
(function (global) {
  'use strict';

  /* 远程能力开关：后端接口就绪后逐个置为 true */
  var REMOTE = {
    instance: false,
    run: false,
    log: false,
    operator: false      // 算子已实现后端接口，但字段模型不同，先本地兜底
  };

  function base() {
    var s = Store.getSettings();
    return (s.apiBase || '').replace(/\/+$/, '');
  }
  function url(path) { return base() + path; }

  function request(path, options, timeoutMs) {
    var opt = options || {};
    var ctrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    if (ctrl) opt.signal = ctrl.signal;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, timeoutMs || 15000);
    return fetch(url(path), opt).then(function (res) {
      clearTimeout(timer);
      return res.text().then(function (text) {
        var body;
        try { body = text ? JSON.parse(text) : null; } catch (e) { body = text; }
        return { ok: res.ok, status: res.status, body: body };
      });
    }, function (err) { clearTimeout(timer); throw err; });
  }

  /* ==================== 实例 ==================== */
  var InstanceApi = {
    list: function () {
      if (!REMOTE.instance) return Promise.resolve({ ok: true, body: Store.listInstances(), local: true });
      return request('/instance/list', { method: 'GET' });
    },
    findById: function (id) {
      if (!REMOTE.instance) return Promise.resolve({ ok: true, body: Store.getInstance(id), local: true });
      return request('/instance/findById?id=' + encodeURIComponent(id), { method: 'GET' });
    },
    save: function (inst) {
      if (!REMOTE.instance) return Promise.resolve({ ok: true, body: Store.saveInstance(inst), local: true });
      return request('/instance/save', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(inst)
      });
    },
    remove: function (ids) {
      if (!REMOTE.instance) {
        (ids || []).forEach(function (id) { Store.removeInstance(id); });
        return Promise.resolve({ ok: true, body: 'success', local: true });
      }
      return request('/instance/delete', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids)
      });
    }
  };

  /* ==================== 算子 ==================== */
  var OperatorApi = {
    list: function (type) {
      if (!REMOTE.operator) return Promise.resolve({ ok: true, body: Store.listOperators(type), local: true });
      return request('/operator/list' + (type ? '?opType=' + encodeURIComponent(type) : ''), { method: 'GET' });
    },
    findById: function (id) {
      if (!REMOTE.operator) return Promise.resolve({ ok: true, body: Store.getOperator(id), local: true });
      return request('/operator/findById?id=' + encodeURIComponent(id), { method: 'GET' });
    },
    save: function (op) {
      if (!REMOTE.operator) return Promise.resolve({ ok: true, body: Store.saveOperator(op), local: true });
      return request('/operator/save', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(op)
      });
    },
    remove: function (ids) {
      if (!REMOTE.operator) {
        Store.removeOperators(ids);
        return Promise.resolve({ ok: true, body: 'success', local: true });
      }
      return request('/operator/delete', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids)
      });
    },
    /**
     * 注册为复用算子（跨实例可见）。
     * 后端契约建议：POST /operator/register  body: [opId]
     */
    register: function (ids) {
      ids = Array.isArray(ids) ? ids : [ids];
      if (!REMOTE.operator) {
        var fail = null;
        ids.forEach(function (id) {
          var r = Store.registerOperator(id);
          if (!r.ok && !fail) fail = r.msg;
        });
        if (fail) return Promise.resolve({ ok: false, msg: fail, local: true });
        return Promise.resolve({ ok: true, body: 'success', local: true });
      }
      return request('/operator/register', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids)
      });
    }
  };

  /* ==================== 环境配置 ====================
   * shell 执行环境 / sql 数据库连接的 CRUD 与连通性测试。
   * 后端契约建议：
   *   GET  /env/list?kind=shell|sql
   *   POST /env/save
   *   POST /env/delete            body: [envId]
   *   POST /env/test              body: { envId }  → { ok, msg, latency }
   * 本地兜底：CRUD 走 Store；test 按协议做尽可能真实的探测（见 testEnv）。
   */
  var REMOTE_ENV = false;

  /**
   * 本地连通性探测。
   * 浏览器无法直接建 TCP/SSH/数据库连接，因此按协议做能力范围内的探测：
   *   - shell：对 host:port 发起 fetch，能连上（即使跨域报错）说明端口开放；
   *   - sql  ：同法探测数据库端口。
   * 明确标注为「端口可达性」而非「登录鉴权」，避免给出误导性结论。
   * 后端 /env/test 就绪后由 REMOTE_ENV 开关接管，做真正的握手与鉴权。
   */
  function probePort(env) {
    var started = Date.now();
    var host = env.host;
    var defPort = { mysql: 3306, postgresql: 5432, oracle: 1521, sqlserver: 1433, dameng: 5236 };
    var port = env.port || (env.kind === 'shell' ? 22 : (defPort[env.dbType] || 3306));
    if (!host) return Promise.resolve({ ok: false, msg: '未配置主机地址', latency: 0 });

    var url = (env.kind === 'shell' ? 'http' : 'http') + '://' + host + ':' + port + '/';
    var ctrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) ctrl.abort(); }, 5000);

    return fetch(url, { method: 'GET', mode: 'no-cors', signal: ctrl ? ctrl.signal : undefined })
      .then(function () {
        clearTimeout(timer);
        return { ok: true, msg: '端口 ' + host + ':' + port + ' 可达', latency: Date.now() - started };
      }, function (err) {
        clearTimeout(timer);
        /* no-cors 下跨域响应会被拦截成 opaque，但连接成功不会进这里；
           走到这里通常是 ECONNREFUSED / DNS 失败 / 超时。 */
        var msg = (err && err.name === 'AbortError')
          ? '连接超时（5s），请检查主机地址与网络'
          : '无法连接 ' + host + ':' + port;
        return { ok: false, msg: msg, latency: Date.now() - started };
      });
  }

  var EnvApi = {
    list: function (kind) {
      if (!REMOTE_ENV) return Promise.resolve({ ok: true, body: Store.listEnvs(kind), local: true });
      return request('/env/list' + (kind ? '?kind=' + encodeURIComponent(kind) : ''), { method: 'GET' });
    },
    save: function (env) {
      if (!REMOTE_ENV) return Promise.resolve({ ok: true, body: Store.saveEnv(env), local: true });
      return request('/env/save', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(env)
      });
    },
    remove: function (id) {
      if (!REMOTE_ENV) return Promise.resolve({ ok: true, body: Store.removeEnv(id), local: true });
      return request('/env/delete', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify([id])
      });
    },
    /** 连通性测试：返回 { ok, msg, latency }，并回写 lastTest 便于回看 */
    test: function (env) {
      var run = REMOTE_ENV
        ? request('/env/test', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ envId: env.id })
        }, 20000)
        : probePort(env);

      return run.then(function (res) {
        var r = (res && res.body !== undefined) ? res.body : res;
        var out = {
          ok: !!(r && (r.ok === true || r.success === true)),
          msg: (r && (r.msg || r.message)) || (r && r.ok ? '连接成功' : '连接失败'),
          latency: (r && r.latency) || 0
        };
        if (!out.ok && out.msg === '连接失败' && res && res.status) {
          out.msg = '测试接口返回 ' + res.status;
        }
        if (env && env.id) Store.markEnvTest(env.id, out);
        return out;
      }, function () {
        var out = { ok: false, msg: '测试请求失败，请确认后端服务可用', latency: 0 };
        if (env && env.id) Store.markEnvTest(env.id, out);
        return out;
      });
    }
  };

  /* ==================== 运行记录 ==================== */
  var RunApi = {
    list: function (instId) {
      if (!REMOTE.run) return Promise.resolve({ ok: true, body: Store.listRuns(instId), local: true });
      return request('/run/list?instId=' + encodeURIComponent(instId), { method: 'GET' });
    },
    findById: function (instId, runId) {
      if (!REMOTE.run) return Promise.resolve({ ok: true, body: Store.getRun(instId, runId), local: true });
      return request('/run/findById?id=' + encodeURIComponent(runId), { method: 'GET' });
    },
    remove: function (instId, runId) {
      if (!REMOTE.run) { Store.removeRun(instId, runId); return Promise.resolve({ ok: true, local: true }); }
      return request('/run/delete', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify([runId])
      });
    }
  };

  /* ==================== 日志 ==================== */
  var LogApi = {
    listByRun: function (runId) {
      if (!REMOTE.log) return Promise.resolve({ ok: true, body: [], local: true });
      return request('/log/list?runId=' + encodeURIComponent(runId), { method: 'GET' });
    },
    listByNode: function (runId, nodeId) {
      if (!REMOTE.log) return Promise.resolve({ ok: true, body: [], local: true });
      return request('/log/nodeLogs?runId=' + encodeURIComponent(runId) + '&nodeId=' + encodeURIComponent(nodeId), { method: 'GET' });
    }
  };

  /* ==================== 运行实例（后端已实现） ==================== */
  /**
   * POST /inst/run/or/getResult/{planId}  (multipart/form-data)
   * 后端保留字段带 `servea_` 前缀；请求头由后端自动采集。
   */
  function runOrGetResult(planId, form) {
    var fd = new FormData();
    fd.append('servea_optType', form.optType || 'run');
    fd.append('servea_sync', (form.sync === true || form.sync === 'true') ? 'true' : 'false');
    if (form.instName) fd.append('servea_instName', form.instName);
    var params = form.params || {};
    Object.keys(params).forEach(function (k) {
      var v = params[k];
      if (v === null || v === undefined) return;
      fd.append(k, typeof v === 'object' ? JSON.stringify(v) : String(v));
    });
    return request('/inst/run/or/getResult/' + encodeURIComponent(planId), { method: 'POST', body: fd }, 60000);
  }

  /* ==================== 算子注册（后端已实现的旧接口） ==================== */
  function saveRegisterInfo(entity) {
    return request('/registerOperator/save', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(entity)
    });
  }
  function deleteRegisterInfo(ids) {
    return request('/registerOperator/delete', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids || [])
    });
  }
  function findRegisterInfo(id) {
    return request('/registerOperator/findById?id=' + encodeURIComponent(id), { method: 'GET' });
  }

  /**
   * 连通性检测：后端无健康检查端点，
   * findById 在 id 不存在时返回 200 null，适合做探针。
   */
  function ping() {
    return request('/registerOperator/findById?id=__ping__', { method: 'GET' }, 5000);
  }

  global.Api = {
    base: base,
    url: url,
    REMOTE: REMOTE,

    // 本地/远程统一入口
    instance: InstanceApi,
    operator: OperatorApi,
    env: EnvApi,
    run: RunApi,
    log: LogApi,

    // 后端已实现
    runOrGetResult: runOrGetResult,
    saveRegisterInfo: saveRegisterInfo,
    deleteRegisterInfo: deleteRegisterInfo,
    findRegisterInfo: findRegisterInfo,
    ping: ping
  };
})(window);
