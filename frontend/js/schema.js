/* ===== JSON Schema 工具 =====
 * 与后端 DslParser.getDslStartParamsTypeMap 解析的 inputsJsonSchema 结构保持一致：
 *   { "type":"object", "properties": { 字段名: { "type":"string", "description":"..." } }, "required":[...] }
 */
(function (global) {
  'use strict';

  var TYPES = ['string', 'number', 'integer', 'boolean', 'object', 'array'];

  /** 由字段行数组生成 JSON Schema 对象 */
  function buildSchema(rows) {
    var properties = {};
    var required = [];
    rows.forEach(function (r) {
      if (!r.name) return;
      var prop = { type: r.type || 'string' };
      if (r.description) prop.description = r.description;
      if (r.type === 'object') { prop.properties = {}; }
      if (r.type === 'array') { prop.items = { type: 'string' }; }
      properties[r.name] = prop;
      if (r.required) required.push(r.name);
    });
    var schema = { type: 'object', properties: properties };
    if (required.length) schema.required = required;
    return schema;
  }

  /** 由 Schema 对象反解字段行数组 */
  function parseSchema(schema) {
    var rows = [];
    if (!schema || typeof schema !== 'object') return rows;
    var props = schema.properties || {};
    var req = schema.required || [];
    Object.keys(props).forEach(function (k) {
      var p = props[k] || {};
      rows.push({
        name: k,
        type: p.type || 'string',
        required: req.indexOf(k) >= 0,
        description: p.description || ''
      });
    });
    return rows;
  }

  /**
   * 取开始节点的 inputsJsonSchema 字符串，解析为 { 字段名: 类型 } 映射。
   * 与 DslParser.getDslStartParamsTypeMap 对齐。
   */
  function getStartParamsTypeMap(dsl) {
    var map = {};
    if (!dsl || !dsl.cells) return map;
    var start = dsl.cells.filter(function (c) { return c.cellType === 'node_start'; })[0];
    if (!start || !start.data || !start.data.inputsJsonSchema) return map;
    var schema = tryParse(start.data.inputsJsonSchema);
    if (!schema) return map;
    var props = schema.properties || {};
    Object.keys(props).forEach(function (k) {
      map[k] = (props[k] && props[k].type) || 'string';
    });
    return map;
  }

  /** 安全解析 JSON 字符串 */
  function tryParse(str) {
    if (str === null || str === undefined) return null;
    if (typeof str === 'object') return str;
    var s = String(str).trim();
    if (!s) return null;
    try { return JSON.parse(s); } catch (e) { return null; }
  }

  /** 把值按声明类型转换（表单字符串 -> 实际类型） */
  function castValue(value, type) {
    if (value === null || value === undefined || value === '') return value;
    switch (type) {
      case 'number': {
        var n = Number(value);
        return isNaN(n) ? value : n;
      }
      case 'integer': {
        var i = parseInt(value, 10);
        return isNaN(i) ? value : i;
      }
      case 'boolean':
        return value === true || value === 'true' || value === '1' || value === 'on';
      case 'object':
      case 'array': {
        var o = tryParse(value);
        return o === null ? value : o;
      }
      default:
        return value;
    }
  }

  /** 校验 Schema 字符串是否合法 JSON */
  function validateSchemaString(str) {
    if (!str) return { ok: true };
    var obj = tryParse(str);
    if (obj === null) return { ok: false, msg: '不是合法的 JSON' };
    if (obj.type !== 'object') return { ok: false, msg: 'type 必须为 "object"' };
    return { ok: true };
  }

  global.SchemaUtil = {
    TYPES: TYPES,
    buildSchema: buildSchema,
    parseSchema: parseSchema,
    getStartParamsTypeMap: getStartParamsTypeMap,
    tryParse: tryParse,
    castValue: castValue,
    validateSchemaString: validateSchemaString
  };
})(window);
