package com.hrh.servicearrange.parser;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.parser.annotation.CellType;

import java.util.HashMap;
import java.util.Map;

/**
 * 把前端 node_op / 短 opType 解析成引擎 cellType。
 */
public final class CellTypeMapper {

    private static final Map<String, String> OP_TYPE_TO_CELL = new HashMap<>();

    static {
        OP_TYPE_TO_CELL.put("http", CellType.OPERATOR_HTTP);
        OP_TYPE_TO_CELL.put("sql", CellType.OPERATOR_SQL);
        OP_TYPE_TO_CELL.put("shell", CellType.FUN_SHELL);
        OP_TYPE_TO_CELL.put("datamap", CellType.FUN_DATAMAP);
        OP_TYPE_TO_CELL.put("transform2obj", CellType.FUN_TRANSFORM);
        OP_TYPE_TO_CELL.put("dataresult", CellType.FUN_DATARESULT);
        OP_TYPE_TO_CELL.put("decision", CellType.FUN_DECISION);
        OP_TYPE_TO_CELL.put("execPython", CellType.FUN_PYTHON);
        OP_TYPE_TO_CELL.put("execShell", CellType.FUN_SHELL);
        OP_TYPE_TO_CELL.put("execJar", CellType.FUN_JAR);
        OP_TYPE_TO_CELL.put("sleep", CellType.FUN_SLEEP);
        OP_TYPE_TO_CELL.put("convertJson2Xml", CellType.FUN_JSON2XML);
        OP_TYPE_TO_CELL.put("convertXml2Json", CellType.FUN_XML2JSON);
        OP_TYPE_TO_CELL.put("compensateDatamap", CellType.FUN_COMPENSATE_DATAMAP);
        OP_TYPE_TO_CELL.put("httpCompensate", CellType.OPERATOR_HTTP_COMPENSATE);
        OP_TYPE_TO_CELL.put("webserviceCompensate", CellType.OPERATOR_WEBSERVICE_COMPENSATE);
        OP_TYPE_TO_CELL.put("dubboCompensate", CellType.OPERATOR_DUBBO_COMPENSATE);
        OP_TYPE_TO_CELL.put("dubbo", CellType.OPERATOR_DUBBO);
        OP_TYPE_TO_CELL.put("webservice", CellType.OPERATOR_WEBSERVICE);
        OP_TYPE_TO_CELL.put(CellType.OPERATOR_HTTP, CellType.OPERATOR_HTTP);
        OP_TYPE_TO_CELL.put(CellType.FUN_DATAMAP, CellType.FUN_DATAMAP);
    }

    private CellTypeMapper() {
    }

    public static String resolve(Cell cell) {
        if (cell == null) {
            return null;
        }
        return resolve(cell.getCellType(), cell.getData());
    }

    public static String resolveExecuteType(Task task) {
        if (task == null) {
            return null;
        }
        return resolve(task.getType(), task.getInputs());
    }

    public static String resolve(String cellType, Object data) {
        JSONObject obj = toObj(data);
        if (obj != null) {
            String embedded = obj.getStr("cellType");
            if (StrUtil.isNotEmpty(embedded) && !CellType.NODE_OP.equals(embedded)) {
                return embedded;
            }
            String opType = obj.getStr("opType");
            if (StrUtil.isNotEmpty(opType)) {
                String mapped = OP_TYPE_TO_CELL.get(opType);
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        if (StrUtil.isEmpty(cellType) || CellType.NODE_OP.equals(cellType)) {
            return CellType.OPERATOR_HTTP;
        }
        String mapped = OP_TYPE_TO_CELL.get(cellType);
        return mapped != null ? mapped : cellType;
    }

    private static JSONObject toObj(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        if (data instanceof String) {
            String s = (String) data;
            if (StrUtil.isEmpty(s) || !JSONUtil.isJsonObj(s)) {
                return null;
            }
            return JSONUtil.parseObj(s);
        }
        try {
            return JSONUtil.parseObj(data);
        } catch (Exception e) {
            return null;
        }
    }
}
