package com.hrh.servicearrange.entity;

import com.hrh.servicearrange.dsl.Cell;
import com.hrh.servicearrange.dsl.Group;
import com.hrh.servicearrange.dsl.KeyValueDto;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.io.Serializable;
import java.util.*;

/**
 * @author huangrenhui
 * @date 2022/3/31
 * @flow
 */
@Document(collection = Inst.TABLE_NAME)
public class Inst extends BaseEntity implements Serializable {

    public static final String TABLE_NAME = "SERVEA_INST";

    private static final long serialVersionUID = 7233975319457919848L;
    /**
     * 同步实例保存
     */
    @Transient
    public static Map<String, Inst> syncInstMap = new HashMap<>();
    /**
     * 实例加锁前缀
     */
    @Transient
    public static final String ZK_LOCK_PREFIX = "/INST_LOCK_";
    /**
     * 实例状态：等待，实例创建完没有开始就是等待状态；
     */
    @Transient
    public static final String STATE_WAITE = "WAITE";
    /**
     * 实例状态：运行中；
     */
    @Transient
    public static final String STATE_RUNNING = "RUNNING";
    /**
     * 实例状态：运行成功；
     */
    @Transient
    public static final String STATE_SUCCESS = "SUCCESS";
    /**
     * 实例状态：运行失败；
     */
    @Transient
    public static final String STATE_FAIL = "FAIL";
    /**
     * 实例状态：暂停，不影响当前运行节点，当前节点执行完下面节点暂停不运行；
     */
    @Transient
    public static final String STATE_SUSPEND = "SUSPEND";
    /**
     * 实例状态：恢复，对暂停节点重新启动运行；
     */
    @Transient
    public static final String STATE_RESUME = "RESUME";

    /**
     * 模型id，当模型创建完根据模型id来运行模型生成实例
     */
    @Field("planId")
    private String planId;
    /**
     * DSL描述
     */
    @Field("dsl")
    private String dsl;
    /**
     * 操作类型 run:运行 debug:调试运行 get_result:获取结果
     */
    @Field("optType")
    private String optType = "run";
    /**
     * 是否同步 true:同步 false:异步
     */
    @Field("sync")
    private Boolean sync = false;
    /**
     * DSL模型入参：实例运行/run/or/getResult接口body参数
     */
    @Field("dslInputParams")
    private String dslInputParams;
    /**
     * header参数集合
     */
    @Field("headerParams")
    private Map<String, String> headerParams;
    /**
     * 静态全局参数(常量)
     */
    @Field("staticParams")
    private List<KeyValueDto> staticParams = new ArrayList<>();
    /**
     * 动态全局参数(变量)
     */
    @Field("dynamicParams")
    private List<KeyValueDto> dynamicParams = new ArrayList<>();

    /**
     * 节点信息集合
     */
    @Field("nodeMap")
    private Map<String, Cell> nodeMap = new HashMap<>();
    /**
     * 根节点：开始节点
     */
    @Field("roots")
    private Set<String> roots = new HashSet<String>();
    /**
     * 父子节点关系集合
     */
    @Field("nodeChildsMap")
    private Map<String, Set<String>> nodeChildsMap = new HashMap<String, Set<String>>();
    /**
     * 子父节点关系集合
     */
    @Field("nodeParentsMap")
    private Map<String, Set<String>> nodeParentsMap = new HashMap<String, Set<String>>(); // 节点 与其直接上级
    /**
     * 节点的入度线
     */
    @Field("nodeInputEdgesMap")
    private Map<String, Set<String>> nodeInputEdgesMap = new HashMap<String, Set<String>>();
    /**
     * 节点的出度度线
     */
    @Field("nodeOutEdgesMap")
    private Map<String, Set<String>> nodeOutEdgesMap = new HashMap<String, Set<String>>();
    /**
     * 实例状态：WAITE RUNNING SUCCESS FAIL SUSPEND RESUME，默认等待
     */
    @Field("state")
    private String state = "WAITE";
    /**
     * 总数
     */
    @Field("totalNodes")
    private Set<String> totalNodes = new HashSet<String>();
    /**
     * 等待数
     */
    @Field("waitingNodes")
    private Set<String> waitingNodes = new HashSet<String>();
    /**
     * 成功数
     */
    @Field("successNodes")
    private Set<String> successNodes = new HashSet<String>();
    /**
     * 失败数
     */
    @Field("failNodes")
    private Set<String> failNodes = new HashSet<String>();
    /**
     * 跳过数
     */
    @Field("skipNodes")
    private Set<String> skipNodes = new HashSet<String>();
    /**
     * 挂起数
     */
    @Field("suspendNodes")
    private Set<String> suspendNodes = new HashSet<String>();
    /**
     * 实例结果信息 json、二进制、单值
     */
    @Field("outputs")
    private Task.Result outputs;
    /**
     * 模型开始运行时间
     */
    @Field("starDate")
    private Date starDate;
    /**
     * 模型结束运行时间
     */
    @Field("endDate")
    private Date endDate;
    /**
     * 补偿强组合集合：一个模型中有多个强组合
     */
    @Field("groups")
    private List<Group> groups;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getDsl() {
        return dsl;
    }

    public void setDsl(String dsl) {
        this.dsl = dsl;
    }

    public String getOptType() {
        return optType;
    }

    public void setOptType(String optType) {
        this.optType = optType;
    }

    public Boolean getSync() {
        return sync;
    }

    public void setSync(Boolean sync) {
        this.sync = sync;
    }

    public String getDslInputParams() {
        return dslInputParams;
    }

    public void setDslInputParams(String dslInputParams) {
        this.dslInputParams = dslInputParams;
    }

    public Map<String, String> getHeaderParams() {
        return headerParams;
    }

    public void setHeaderParams(Map<String, String> headerParams) {
        this.headerParams = headerParams;
    }

    public List<KeyValueDto> getStaticParams() {
        return staticParams;
    }

    public void setStaticParams(List<KeyValueDto> staticParams) {
        this.staticParams = staticParams;
    }

    public List<KeyValueDto> getDynamicParams() {
        return dynamicParams;
    }

    public void setDynamicParams(List<KeyValueDto> dynamicParams) {
        this.dynamicParams = dynamicParams;
    }

    public Map<String, Cell> getNodeMap() {
        return nodeMap;
    }

    public void setNodeMap(Map<String, Cell> nodeMap) {
        this.nodeMap = nodeMap;
    }

    public Set<String> getRoots() {
        return roots;
    }

    public void setRoots(Set<String> roots) {
        this.roots = roots;
    }

    public Map<String, Set<String>> getNodeChildsMap() {
        return nodeChildsMap;
    }

    public void setNodeChildsMap(Map<String, Set<String>> nodeChildsMap) {
        this.nodeChildsMap = nodeChildsMap;
    }

    public Map<String, Set<String>> getNodeParentsMap() {
        return nodeParentsMap;
    }

    public void setNodeParentsMap(Map<String, Set<String>> nodeParentsMap) {
        this.nodeParentsMap = nodeParentsMap;
    }

    public Map<String, Set<String>> getNodeInputEdgesMap() {
        return nodeInputEdgesMap;
    }

    public void setNodeInputEdgesMap(Map<String, Set<String>> nodeInputEdgesMap) {
        this.nodeInputEdgesMap = nodeInputEdgesMap;
    }

    public Map<String, Set<String>> getNodeOutEdgesMap() {
        return nodeOutEdgesMap;
    }

    public void setNodeOutEdgesMap(Map<String, Set<String>> nodeOutEdgesMap) {
        this.nodeOutEdgesMap = nodeOutEdgesMap;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Set<String> getTotalNodes() {
        return totalNodes;
    }

    public void setTotalNodes(Set<String> totalNodes) {
        this.totalNodes = totalNodes;
    }

    public Set<String> getWaitingNodes() {
        return waitingNodes;
    }

    public void setWaitingNodes(Set<String> waitingNodes) {
        this.waitingNodes = waitingNodes;
    }

    public Set<String> getSuccessNodes() {
        return successNodes;
    }

    public void setSuccessNodes(Set<String> successNodes) {
        this.successNodes = successNodes;
    }

    public Set<String> getFailNodes() {
        return failNodes;
    }

    public void setFailNodes(Set<String> failNodes) {
        this.failNodes = failNodes;
    }

    public Set<String> getSkipNodes() {
        return skipNodes;
    }

    public void setSkipNodes(Set<String> skipNodes) {
        this.skipNodes = skipNodes;
    }

    public Set<String> getSuspendNodes() {
        return suspendNodes;
    }

    public void setSuspendNodes(Set<String> suspendNodes) {
        this.suspendNodes = suspendNodes;
    }

    public Task.Result getOutputs() {
        return outputs;
    }

    public void setOutputs(Task.Result outputs) {
        this.outputs = outputs;
    }

    public Date getStarDate() {
        return starDate;
    }

    public void setStarDate(Date starDate) {
        this.starDate = starDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public void setGroups(List<Group> groups) {
        this.groups = groups;
    }
}
