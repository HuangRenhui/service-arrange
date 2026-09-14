package com.hrh.servicearrange.parser;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.dsl.DSL;
import com.hrh.servicearrange.dsl.StartCell;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow
 */
@Component
public class DslParser {

    public final static List<String> CELL_STARTEND_TYPES = Arrays.asList(CellType.START, CellType.END);
    public final static List<String> CELL_EDGE_TYPES = Arrays.asList(CellType.EDGE_COMMON, CellType.EDGE_LOOP, CellType.EDGE_DECISION, CellType.EDGE_COMPENSATE);
    public final static List<String> CELL_COMPENSATE_TYPES = Arrays.asList(CellType.OPERATOR_HTTP_COMPENSATE, CellType.OPERATOR_DUBBO_COMPENSATE, CellType.OPERATOR_WEBSERVICE_COMPENSATE);

    public static Map<String, String> getDslStartParamsTypeMap(String dslString) {
        Map<String, String> result = new HashMap<>();
        DSL dsl = JSONUtil.toBean(dslString, DSL.class);
        Cell start = dsl.getCells().stream().filter(c -> CellType.START.equals(c.getCellType())).findFirst().get();
        StartCell.Data data = JSONUtil.toBean(JSONUtil.toJsonStr(start.getData()), StartCell.Data.class);
        JSONObject inputsJsonSchema = JSONUtil.parseObj(data.getInputsJsonSchema(), JsonSchemaUtil.jsonConfig);
        JSONObject properties = inputsJsonSchema.getJSONObject("properties");
        if (properties != null) {
            properties.keySet().stream().forEach(key -> {
                JSONObject typObject = properties.getJSONObject(key);
                String type = typObject.getStr("type");
                result.put(key, type);
            });
        }
        return result;
    }

    public Inst parser(String dslStr) {
        DSL dsl = JSONUtil.toBean(dslStr, DSL.class);
        System.out.println("dsl："+JSONUtil.toJsonStr(dsl));
        //获取所有节点，排除全局参数节点
        List<Cell> cells = dsl.getCells().stream().filter(c -> !CellType.GLOBAL_DATA.equals(c.getCellType())).collect(Collectors.toList());
        //获取全局参数节点
        List<Cell> globalDatas = dsl.getCells().stream().filter(c -> CellType.GLOBAL_DATA.equals(c.getCellType())).collect(Collectors.toList());
        //获取开始、结束节点id
        Set<String> endpointNodeIds = dsl.getCells().stream().filter(c -> CELL_STARTEND_TYPES.contains(c.getCellType())).map(Cell::getId).collect(Collectors.toSet());
        //获取所有边信息
        List<Cell> edges = dsl.getCells().stream().filter(c -> CELL_EDGE_TYPES.contains(c.getCellType())).collect(Collectors.toList());
        //source、target节点信息存储在线中
        //获取所有源source节点
        Set<String> srcNodeIds = edges.stream().filter(c -> !CellType.EDGE_LOOP.equals(c.getCellType())).map(ecc -> ecc.getSource().getCell()).collect(Collectors.toSet());
        //获取所有目标target节点
        Set<String> tarNodeIds = edges.stream().filter(c -> !CellType.EDGE_LOOP.equals(c.getCellType())).map(ecc -> ecc.getTarget().getCell()).collect(Collectors.toSet());
        //获取独立节点（除了开始、结束、边、源、目标节点）
        Set<String> independs = cells.stream().filter(c -> !CELL_STARTEND_TYPES.contains(c.getCellType()))
                .filter(c -> !CELL_EDGE_TYPES.contains(c.getCellType()))
                .filter(c -> !srcNodeIds.contains(c.getId()))
                .filter(c -> !tarNodeIds.contains(c.getId()))
                .map(Cell::getId)
                .distinct().collect(Collectors.toSet());
        //获取根节点

        //获取结束节点
        //
        return null;
    }
}
