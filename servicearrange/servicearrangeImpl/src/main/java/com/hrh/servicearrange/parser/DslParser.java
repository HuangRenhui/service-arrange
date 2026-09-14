package com.hrh.servicearrange.parser;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.dsl.*;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.NodeLoopInfo;
import com.hrh.servicearrange.parser.annotation.CellType;
import com.hrh.servicearrange.utils.JsonSchemaUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
        System.out.println("dsl：" + JSONUtil.toJsonStr(dsl));
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
        //获取独立节点（除了开始、结束、边、源、目标节点）,没有关联关系的独立节点（A-B-C-D,E，其中E是独立节点）
        Set<String> independs = cells.stream().filter(c -> !CELL_STARTEND_TYPES.contains(c.getCellType()))
                .filter(c -> !CELL_EDGE_TYPES.contains(c.getCellType()))
                .filter(c -> !srcNodeIds.contains(c.getId()))
                .filter(c -> !tarNodeIds.contains(c.getId()))
                .map(Cell::getId)
                .distinct().collect(Collectors.toSet());
        System.out.println("independs：" + JSONUtil.toJsonStr(independs));
        //获取根节点：画布中实际的根节点（开始节点隐藏）
        List<Cell> roots = cells.stream().filter(n -> srcNodeIds.contains(n.getId())).filter(n -> !tarNodeIds.contains(n.getId())).collect(Collectors.toList());
        System.out.println("roots：" + JSONUtil.toJsonStr(roots));
        //获取结束节点：画布中实际结束的节点（结束节点隐藏）
        List<Cell> ends = cells.stream().filter(n -> tarNodeIds.contains(n.getId())).filter(n -> !srcNodeIds.contains(n.getId())).collect(Collectors.toList());
        System.out.println("ends：" + JSONUtil.toJsonStr(ends));
        //父子节点关系
        Map<String, Set<String>> nodeChildsMap = new HashMap<>();
        //子父节点关系
        Map<String, Set<String>> nodeParentsMap = new HashMap<>();
        //节点的入度线：线target、线id
        Map<String, Set<String>> nodeInputEdgesMap = new HashMap<>();
        //节点的出度线：线source、线id
        Map<String, Set<String>> nodeOutEdgesMap = new HashMap<>();
        //所有节点，包含边节点
        Map<String, Cell> nodeMap = new HashMap<>();
        cells.stream().forEach(c -> nodeMap.put(c.getId(), c));
        //独立节点加入根、结束节点集合
        if (independs != null && independs.size() > 0) {
            independs.stream().forEach(i -> roots.add(nodeMap.get(i)));
            independs.stream().forEach(i -> ends.add(nodeMap.get(i)));
        }
        //处理父子节点、子父节点、线入度、线出度
        edges.stream().forEach(ecc -> {
            EdgeCommonCell.EdgeEndpoint source = ecc.getSource();
            EdgeCommonCell.EdgeEndpoint target = ecc.getTarget();
            if (nodeChildsMap.containsKey(source.getCell())) {
                nodeChildsMap.get(source.getCell()).add(target.getCell());
            } else {
                HashSet<String> set = new HashSet<>();
                set.add(target.getCell());
                nodeChildsMap.put(source.getCell(), set);
            }
            if (nodeParentsMap.containsKey(target.getCell())) {
                nodeParentsMap.get(target.getCell()).add(source.getCell());
            } else {
                HashSet<String> set = new HashSet<>();
                set.add(source.getCell());
                nodeParentsMap.put(target.getCell(), set);
            }
            if (nodeInputEdgesMap.containsKey(target.getCell())) {
                nodeInputEdgesMap.get(target.getCell()).add(ecc.getId());
            } else {
                HashSet<String> set = new HashSet<>();
                set.add(ecc.getId());
                nodeInputEdgesMap.put(target.getCell(), set);
            }
            if (nodeOutEdgesMap.containsKey(source.getCell())) {
                nodeOutEdgesMap.get(source.getCell()).add(ecc.getId());
            } else {
                HashSet<String> set = new HashSet<>();
                set.add(ecc.getId());
                nodeOutEdgesMap.put(source.getCell(), set);
            }
        });
        System.out.println("nodeChildsMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("nodeParentsMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("nodeInputEdgesMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("nodeOutEdgesMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("===================开始节点==========================");
        //开始节点：隐藏开始节点-真正开始节点的父子、子父关系
        String startId = cells.stream().filter(c -> CellType.START.equals(c.getCellType())).findFirst().get().getId();
        Set<String> rootIds = roots.stream().map(Cell::getId).collect(Collectors.toSet());
        rootIds = rootIds.stream().filter(id -> !startId.equals(id)).collect(Collectors.toSet());
        if (!rootIds.isEmpty()) {
            nodeChildsMap.put(startId, rootIds);
        }
        Set<String> startSet = new HashSet<>();
        startSet.add(startId);
        rootIds.stream().forEach(i -> nodeParentsMap.put(i, startSet));
        System.out.println("nodeChildsMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("nodeParentsMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("==================结束节点===========================");
        //结束节点：隐藏结束-真正结束父子、子父
        String endId = cells.stream().filter(c -> CellType.END.equals(c.getCellType())).findFirst().get().getId();
        Set<String> endIds = ends.stream().map(Cell::getId).collect(Collectors.toSet());
        nodeChildsMap.put(endId, endIds);
        Set<String> endSet = new HashSet<>();
        endSet.add(endId);
        endIds.stream().forEach(i -> nodeParentsMap.put(i, endSet));
        System.out.println("nodeChildsMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("nodeParentsMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("=================隐藏开始-隐藏结束============================");
        //隐藏开始-隐藏结束父子、子父关系
        nodeChildsMap.get(startId).add(endId);
        nodeParentsMap.get(endId).add(startId);
        System.out.println("nodeChildsMap：" + JSONUtil.toJsonStr(ends));
        System.out.println("nodeParentsMap：" + JSONUtil.toJsonStr(ends));
        //处理强组合:dsl有一个强组合节点，对应节点有强组合节点的映射id
        List<Group> groups = dsl.getGroups();
        if (groups != null && groups.size() > 0) {
            //dsl的强组合节点
            Map<String, Group> groupMap = groups.stream().collect(Collectors.toMap(Group::getId, g -> g));
            //节点的强组合映射
            cells.stream().forEach(c -> {
                if (c.getGroupIds() != null) {
                    c.getGroupIds().stream().forEach(gid -> groupMap.get(gid).getNodes().add(c.getId()));
                }
            });
            //组装强组合id和对应节点关系
            groups = groupMap.entrySet().stream().map(e -> e.getValue()).collect(Collectors.toList());
        }
        Inst inst = new Inst();
        inst.setDsl(dslStr);
        inst.getRoots().add(startId);
        inst.setNodeChildsMap(nodeChildsMap);
        inst.setNodeParentsMap(nodeParentsMap);
        inst.setNodeInputEdgesMap(nodeInputEdgesMap);
        inst.setNodeOutEdgesMap(nodeOutEdgesMap);
        inst.setTotalNodes(cells.stream().map(Cell::getId).collect(Collectors.toSet()));
        inst.setWaitingNodes(cells.stream().map(Cell::getId).collect(Collectors.toSet()));
        inst.setNodeMap(nodeMap);
        inst.setGroups(groups);
        //全局参数设定
        if (globalDatas != null && globalDatas.size() > 0) {
            GlobalDataCell globalDataCell = JSONUtil.toBean(JSONUtil.toJsonStr(globalDatas.get(0)), GlobalDataCell.class);
            /**
             *
             {
             "type": "string",
             "default": "111",
             "name": "text",
             "nodeKey": 11,
             "id": "f52ce5e3-8686-475b-842f-902de0a72898"
             }
             */
            //全局静态参数
            globalDataCell.getData().getStaticParams().stream().forEach(map -> {
                JSONObject obj = JSONUtil.parseObj(map);
                KeyValueDto dto = new KeyValueDto();
                String name = obj.getStr("name");
                //去除tab等空白字符
                name = StringUtils.isEmpty(name) ? null : StrUtil.trim(name).replace("\t", "").replace("\r", "");
                dto.setName(name);
                dto.setKey(name);
                dto.setDefaultValue(obj.getStr("default"));
                dto.setType(obj.getStr("type"));
                dto.setValue(obj.getStr("default"));
                inst.getStaticParams().add(dto);
            });
            //全局动态参数
            globalDataCell.getData().getDynamicParams().stream().forEach(map -> {
                JSONObject obj = JSONUtil.parseObj(map);
                KeyValueDto dto = new KeyValueDto();
                dto.setName(obj.getStr("name"));
                dto.setKey(obj.getStr("name"));
                dto.setDefaultValue(obj.getStr("default"));
                dto.setType(obj.getStr("type"));
                dto.setValue(obj.getStr("default"));
                inst.getDynamicParams().add(dto);
            });
        }
        //处理循环线之间的节点，包含边
        dsl.getCells().stream().filter(c -> CellType.EDGE_LOOP.equals(c.getCellType())).forEach(edge -> {
            Set<String> ids = getCellIdsBetweenLoopEdge(inst, edge);
            //记录循环线之间节点循环执行的次数
            inst.getLoopRunTimesMap().put(edge.getId(), new NodeLoopInfo(edge.getId(), ids, 0));
        });
        return inst;
    }

    public static Set<String> getCellIdsBetweenLoopEdge(Inst inst, Cell loopCell) {
        //循环线的入度节点
        String source = loopCell.getSource().getCell();
        //获取入度的所有父节点
        Set<String> myParents = new HashSet<>();
        getMyParents(myParents, inst.getNodeParentsMap().get(source), inst);
        //循环线的出度节点
        String target = loopCell.getTarget().getCell();
        //父子节点关系
        Map<String, Set<String>> nodeChildsMap = inst.getNodeChildsMap();
        //获取出度的所有子节点，子节点<=父节点（父节点多了一个出度父节点）
        Set<String> idsbetween = new HashSet<>();
        idsbetween.add(source);
        idsbetween.add(target);
        getChildNotInSet(idsbetween, target, nodeChildsMap, myParents);
        //添加边：先从所有节点中获取边节点，然后出入度节点都包含在循环线间节点的线添加进来；
        inst.getNodeMap().entrySet().stream().filter(e -> CELL_EDGE_TYPES.contains(e.getValue().getCellType()))
                .filter(e -> idsbetween.contains(e.getValue().getSource().getCell()) && idsbetween.contains(e.getValue().getTarget().getCell()))
                .forEach(e -> idsbetween.add(e.getKey()));
        return idsbetween;
    }

    private static int Chilednum = -1;

    private static void getChildNotInSet(Set<String> idsbetween, String target, Map<String, Set<String>> nodeChildsMap, Set<String> myParents) {
        //获取入度的所有子节点
        List<String> childs = nodeChildsMap.get(target).stream().filter(i -> myParents.contains(i)).collect(Collectors.toList());
        if (childs != null && childs.size() > 0) {
            idsbetween.addAll(childs);
            Chilednum++;
            if (idsbetween.size() <= Chilednum) {
                return;
            }
            childs.stream().forEach(i -> getChildNotInSet(idsbetween, i, nodeChildsMap, myParents));
        }
        Chilednum = -1;
    }

    //递归标志
    private static int Parentnum = -1;

    private static void getMyParents(Set<String> myParents, Set<String> pids, Inst inst) {
        if (!StringUtils.isEmpty(pids)) {
            pids.stream().forEach(id -> {
                Parentnum++;
                myParents.add(id);
                //当添加的id个数不在变化时退出递归
                if (myParents.size() <= Parentnum) {
                    return;
                }
                Set<String> pids2 = inst.getNodeParentsMap().get(id);
                getMyParents(myParents, pids2, inst);
            });
        }
        Parentnum = -1;
    }
}
