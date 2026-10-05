package com.hrh.servicearrange.parser;

import cn.hutool.core.io.FileUtil;
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
        if (StrUtil.isEmpty(dslString)) {
            return result;
        }
        DSL dsl = JSONUtil.toBean(dslString, DSL.class);
        if (dsl == null || dsl.getCells() == null) {
            return result;
        }
        Cell start = dsl.getCells().stream().filter(c -> CellType.START.equals(c.getCellType())).findFirst().orElse(null);
        if (start == null || start.getData() == null) {
            return result;
        }
        StartCell.Data data = JSONUtil.toBean(JSONUtil.toJsonStr(start.getData()), StartCell.Data.class);
        if (data == null || StrUtil.isEmpty(data.getInputsJsonSchema())) {
            return result;
        }
        JSONObject inputsJsonSchema = JSONUtil.parseObj(data.getInputsJsonSchema(), JsonSchemaUtil.jsonConfig);
        JSONObject properties = inputsJsonSchema.getJSONObject("properties");
        if (properties != null) {
            properties.keySet().forEach(key -> {
                JSONObject typObject = properties.getJSONObject(key);
                if (typObject != null) {
                    result.put(key, typObject.getStr("type"));
                }
            });
        }
        return result;
    }

    public static void main(String[] args) {
        String s = FileUtil.readUtf8String("D:\\projectManager\\dagproject\\servicearrange\\main\\src\\main\\resources\\hrh_http.json");
        System.out.println(s);
        DslParser dslParser = new DslParser();
        dslParser.parser(s);
    }

    public Inst parser(String dslStr) {
        if (StrUtil.isEmpty(dslStr)) {
            throw new IllegalArgumentException("DSL 为空");
        }
        DSL dsl = JSONUtil.toBean(dslStr, DSL.class);
        if (dsl == null || dsl.getCells() == null) {
            throw new IllegalArgumentException("DSL 结构非法：缺少 cells 数组");
        }
        System.out.println("dsl：" + JSONUtil.toJsonStr(dsl));
        List<Cell> cells = dsl.getCells().stream().filter(c -> c != null && !CellType.GLOBAL_DATA.equals(c.getCellType())).collect(Collectors.toList());
        List<Cell> globalDatas = dsl.getCells().stream().filter(c -> c != null && CellType.GLOBAL_DATA.equals(c.getCellType())).collect(Collectors.toList());
        List<Cell> edges = dsl.getCells().stream()
                .filter(c -> c != null && CELL_EDGE_TYPES.contains(c.getCellType()))
                .filter(c -> c.getSource() != null && StrUtil.isNotEmpty(c.getSource().getCell())
                        && c.getTarget() != null && StrUtil.isNotEmpty(c.getTarget().getCell()))
                .collect(Collectors.toList());
        Cell startCell = cells.stream().filter(c -> CellType.START.equals(c.getCellType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("缺少开始节点 node_start"));
        Cell endCell = cells.stream().filter(c -> CellType.END.equals(c.getCellType())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("缺少结束节点 node_end"));
        Set<String> srcNodeIds = edges.stream().filter(c -> !CellType.EDGE_LOOP.equals(c.getCellType())).map(ecc -> ecc.getSource().getCell()).collect(Collectors.toSet());
        Set<String> tarNodeIds = edges.stream().filter(c -> !CellType.EDGE_LOOP.equals(c.getCellType())).map(ecc -> ecc.getTarget().getCell()).collect(Collectors.toSet());
        Set<String> independs = cells.stream().filter(c -> !CELL_STARTEND_TYPES.contains(c.getCellType()))
                .filter(c -> !CELL_EDGE_TYPES.contains(c.getCellType()))
                .filter(c -> !srcNodeIds.contains(c.getId()))
                .filter(c -> !tarNodeIds.contains(c.getId()))
                .map(Cell::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        System.out.println("independs：" + JSONUtil.toJsonStr(independs));
        List<Cell> roots = cells.stream().filter(n -> srcNodeIds.contains(n.getId())).filter(n -> !tarNodeIds.contains(n.getId())).collect(Collectors.toList());
        List<Cell> ends = cells.stream().filter(n -> tarNodeIds.contains(n.getId())).filter(n -> !srcNodeIds.contains(n.getId())).collect(Collectors.toList());
        Map<String, Set<String>> nodeChildsMap = new HashMap<>();
        Map<String, Set<String>> nodeParentsMap = new HashMap<>();
        Map<String, Set<String>> nodeInputEdgesMap = new HashMap<>();
        Map<String, Set<String>> nodeOutEdgesMap = new HashMap<>();
        Map<String, Cell> nodeMap = new HashMap<>();
        cells.forEach(c -> {
            if (c.getId() != null) {
                nodeMap.put(c.getId(), c);
            }
        });
        if (!independs.isEmpty()) {
            independs.forEach(i -> {
                Cell independent = nodeMap.get(i);
                if (independent != null) {
                    roots.add(independent);
                    ends.add(independent);
                }
            });
        }
        edges.forEach(ecc -> {
            String sourceId = ecc.getSource().getCell();
            String targetId = ecc.getTarget().getCell();
            nodeChildsMap.computeIfAbsent(sourceId, k -> new HashSet<>()).add(targetId);
            nodeParentsMap.computeIfAbsent(targetId, k -> new HashSet<>()).add(sourceId);
            nodeInputEdgesMap.computeIfAbsent(targetId, k -> new HashSet<>()).add(ecc.getId());
            nodeOutEdgesMap.computeIfAbsent(sourceId, k -> new HashSet<>()).add(ecc.getId());
        });
        String startId = startCell.getId();
        String endId = endCell.getId();
        Set<String> rootIds = roots.stream().map(Cell::getId).filter(id -> !startId.equals(id)).collect(Collectors.toSet());
        Set<String> startChildren = nodeChildsMap.computeIfAbsent(startId, k -> new HashSet<>());
        startChildren.addAll(rootIds);
        startChildren.add(endId);
        Set<String> startSet = new HashSet<>();
        startSet.add(startId);
        rootIds.forEach(i -> nodeParentsMap.put(i, new HashSet<>(startSet)));
        Set<String> endIds = ends.stream().map(Cell::getId).filter(id -> !endId.equals(id)).collect(Collectors.toSet());
        nodeChildsMap.put(endId, new HashSet<>(endIds));
        Set<String> endSet = new HashSet<>();
        endSet.add(endId);
        endIds.forEach(i -> nodeParentsMap.put(i, new HashSet<>(endSet)));
        nodeParentsMap.computeIfAbsent(endId, k -> new HashSet<>()).add(startId);
        List<Group> groups = dsl.getGroups();
        if (groups != null && !groups.isEmpty()) {
            Map<String, Group> groupMap = groups.stream().filter(g -> g.getId() != null).collect(Collectors.toMap(Group::getId, g -> g, (a, b) -> a));
            cells.forEach(c -> {
                if (c.getGroupIds() != null) {
                    c.getGroupIds().forEach(gid -> {
                        Group group = groupMap.get(gid);
                        if (group != null) {
                            if (group.getNodes() == null) {
                                group.setNodes(new ArrayList<>());
                            }
                            group.getNodes().add(c.getId());
                        }
                    });
                }
            });
            groups = new ArrayList<>(groupMap.values());
        }
        Inst inst = new Inst();
        inst.setDsl(dslStr);
        inst.getRoots().add(startId);
        inst.setNodeChildsMap(nodeChildsMap);
        inst.setNodeParentsMap(nodeParentsMap);
        inst.setNodeInputEdgesMap(nodeInputEdgesMap);
        inst.setNodeOutEdgesMap(nodeOutEdgesMap);
        inst.setTotalNodes(cells.stream().map(Cell::getId).filter(Objects::nonNull).collect(Collectors.toSet()));
        inst.setWaitingNodes(cells.stream().map(Cell::getId).filter(Objects::nonNull).collect(Collectors.toSet()));
        inst.setNodeMap(nodeMap);
        inst.setGroups(groups);
        if (!globalDatas.isEmpty()) {
            GlobalDataCell globalDataCell = JSONUtil.toBean(JSONUtil.toJsonStr(globalDatas.get(0)), GlobalDataCell.class);
            if (globalDataCell.getData() != null) {
                if (globalDataCell.getData().getStaticParams() != null) {
                    globalDataCell.getData().getStaticParams().forEach(map -> addGlobalParam(inst.getStaticParams(), map, true));
                }
                if (globalDataCell.getData().getDynamicParams() != null) {
                    globalDataCell.getData().getDynamicParams().forEach(map -> addGlobalParam(inst.getDynamicParams(), map, false));
                }
            }
        }
        if (startCell.getData() != null) {
            StartCell.Data startData = JSONUtil.toBean(JSONUtil.toJsonStr(startCell.getData()), StartCell.Data.class);
            if (startData != null && startData.getGlobals() != null) {
                startData.getGlobals().forEach(g -> {
                    if (g == null) {
                        return;
                    }
                    if (StrUtil.isEmpty(g.getKey())) {
                        g.setKey(g.getName());
                    }
                    if (g.getValue() == null) {
                        g.setValue(g.getDefaultValue());
                    }
                    inst.getDynamicParams().add(g);
                });
            }
        }
        dsl.getCells().stream().filter(c -> c != null && CellType.EDGE_LOOP.equals(c.getCellType())).forEach(edge -> {
            Set<String> ids = getCellIdsBetweenLoopEdge(inst, edge);
            inst.getLoopRunTimesMap().put(edge.getId(), new NodeLoopInfo(edge.getId(), ids, 0));
        });
        return inst;
    }

    private void addGlobalParam(List<KeyValueDto> target, Object map, boolean trimName) {
        JSONObject obj = JSONUtil.parseObj(map);
        KeyValueDto dto = new KeyValueDto();
        String name = obj.getStr("name");
        if (trimName) {
            name = StringUtils.isEmpty(name) ? null : StrUtil.trim(name).replace("\t", "").replace("\r", "");
        }
        dto.setName(name);
        dto.setKey(name);
        dto.setDefaultValue(obj.getStr("default"));
        dto.setType(obj.getStr("type"));
        dto.setValue(obj.getStr("default"));
        target.add(dto);
    }

    public static Set<String> getCellIdsBetweenLoopEdge(Inst inst, Cell loopCell) {
        Set<String> idsbetween = new HashSet<>();
        if (inst == null || loopCell == null || loopCell.getSource() == null || loopCell.getTarget() == null) {
            return idsbetween;
        }
        String source = loopCell.getSource().getCell();
        String target = loopCell.getTarget().getCell();
        if (StrUtil.isEmpty(source) || StrUtil.isEmpty(target) || inst.getNodeParentsMap() == null) {
            return idsbetween;
        }
        Set<String> myParents = new HashSet<>();
        getMyParents(myParents, inst.getNodeParentsMap().get(source), inst);
        idsbetween.add(source);
        idsbetween.add(target);
        getChildNotInSet(idsbetween, target, inst.getNodeChildsMap(), myParents);
        if (inst.getNodeMap() != null) {
            inst.getNodeMap().entrySet().stream()
                    .filter(e -> e.getValue() != null && CELL_EDGE_TYPES.contains(e.getValue().getCellType()))
                    .filter(e -> e.getValue().getSource() != null && e.getValue().getTarget() != null)
                    .filter(e -> idsbetween.contains(e.getValue().getSource().getCell()) && idsbetween.contains(e.getValue().getTarget().getCell()))
                    .forEach(e -> idsbetween.add(e.getKey()));
        }
        return idsbetween;
    }

    private static void getChildNotInSet(Set<String> idsbetween, String target, Map<String, Set<String>> nodeChildsMap, Set<String> myParents) {
        if (nodeChildsMap == null || target == null) {
            return;
        }
        Set<String> next = nodeChildsMap.get(target);
        if (next == null || next.isEmpty() || myParents == null) {
            return;
        }
        List<String> childs = next.stream().filter(myParents::contains).collect(Collectors.toList());
        for (String i : childs) {
            if (idsbetween.add(i)) {
                getChildNotInSet(idsbetween, i, nodeChildsMap, myParents);
            }
        }
    }

    private static void getMyParents(Set<String> myParents, Set<String> pids, Inst inst) {
        if (pids == null || pids.isEmpty() || inst.getNodeParentsMap() == null) {
            return;
        }
        for (String id : pids) {
            if (id == null || !myParents.add(id)) {
                continue;
            }
            getMyParents(myParents, inst.getNodeParentsMap().get(id), inst);
        }
    }
}
