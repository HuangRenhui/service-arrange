package com.hrh.servicearrange.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.config.OrchestrationProperties;
import com.hrh.servicearrange.convert.StartTask;
import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.PlanDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Plan;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.lock.PlanRunLock;
import com.hrh.servicearrange.mq.TaskProductor;
import com.hrh.servicearrange.parser.DslParser;
import com.hrh.servicearrange.utils.TraceContext;
import com.hrh.servicearrange.vo.InstRunParamsVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.support.StandardMultipartHttpServletRequest;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 实例启动编排：幂等命中判断、实例落库、流程准入、文件落盘、开始节点投递与失败回收。
 * <p>
 * 这些步骤顺序敏感（先落库拿 id → 抢锁 → 文件落盘 → 投递开始节点），集中在一处避免控制器与编排细节耦合。
 *
 * @author huangrenhui
 */
@Slf4j
@Service
public class InstRunService {

    @Autowired
    private DslParser dslParser;
    @Autowired
    private InstDao instDao;
    @Autowired
    private StartTask startTask;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private TaskProductor taskProductor;
    @Autowired
    private PlanDao planDao;
    @Autowired
    private OrchestrationProperties orchestration;
    @Autowired
    private PlanRunLock planRunLock;
    @Autowired
    private InstFileService instFileService;

    /**
     * 创建并启动实例。
     *
     * @param planId           流程 id
     * @param params           运行参数（含 optType / sync / instName）
     * @param paramObj         已按 schema 转换的入参
     * @param idempotencyKey   幂等键，可为空
     * @param headerParams     请求头，写入实例用于排查
     * @param multipartRequest 为空的 multipart 请求表示无文件入参
     * @return 实例与是否幂等命中
     */
    public RunResult run(String planId, InstRunParamsVo params, JSONObject paramObj, String idempotencyKey,
                         Map<String, String> headerParams, StandardMultipartHttpServletRequest multipartRequest) {
        if (!StringUtils.isEmpty(idempotencyKey)) {
            Inst existed = instDao.findFirstByPlanIdAndIdempotencyKeyOrderByCreateDateDesc(planId, idempotencyKey);
            if (existed != null && !Inst.STATE_FAIL.equals(existed.getState())
                    && !Inst.STATE_CANCEL.equals(existed.getState())) {
                return new RunResult(existed, true);
            }
        }
        //dsl解析
        Inst inst = dslParser.parser(params.getDsl());
        inst.setPlanId(planId);
        inst.setOptType(params.getOptType());
        inst.setSync(params.getSync());
        inst.setDslInputParams(JSONUtil.toJsonStr(paramObj));
        Date date = new Date();
        inst.setCreateDate(date);
        inst.setModifyDate(date);
        inst.setHeaderParams(headerParams);
        inst.setTraceId(TraceContext.getOrCreate());
        inst.setIdempotencyKey(idempotencyKey);
        inst.setCallerApp(firstHeader(headerParams, "x-caller-app"));
        Plan planMeta = planDao.findById(planId).orElse(null);
        if (planMeta != null) {
            inst.setPlanVersion(planMeta.getVersion());
            inst.setEnv(planMeta.getEnv());
        }
        try {
            instDao.save(inst);
        } catch (DuplicateKeyException e) {
            //并发提交同一幂等键：上面的查询可能都没命中，由唯一索引兜底，回读已存在实例并返回
            if (!StringUtils.isEmpty(idempotencyKey)) {
                Inst existed = instDao.findFirstByPlanIdAndIdempotencyKeyOrderByCreateDateDesc(planId, idempotencyKey);
                if (existed != null) {
                    return new RunResult(existed, true);
                }
            }
            throw e;
        }
        //流程准入：同一 planId 同时只允许一个运行中实例（ZK 原子抢占，抢不到直接 400）
        boolean lockHeld = acquireRunLock(inst, planId);
        try {
            //上传文件落盘：实例 id 已生成，文件按实例目录存放，参数值回填 local:// 地址
            if (null != multipartRequest) {
                instFileService.storeUploadFiles(multipartRequest, inst.getId(), paramObj);
                inst.setDslInputParams(JSONUtil.toJsonStr(paramObj));
                inst.setModifyDate(new Date());
                instDao.save(inst);
            }
            //开始运行实例
            if (isRun(params.getOptType())) {
                start(inst);
            }
        } catch (RuntimeException e) {
            //运行启动失败：WAITE 态的半成品实例连同文件一起回收；已进入 RUNNING 的保留，由人工取消释放锁
            if (!Inst.STATE_RUNNING.equals(inst.getState())) {
                instFileService.deleteByInstId(inst.getId());
                instDao.deleteById(inst.getId());
                if (lockHeld) {
                    planRunLock.release(planId, inst.getId());
                }
                log.warn("实例启动失败，已回收半成品实例, planId={}, instId={}", planId, inst.getId());
            }
            throw e;
        }
        return new RunResult(inst, false);
    }

    /**
     * 从虚拟开始节点投递任务。
     */
    private void start(Inst inst) {
        String startId = inst.getRoots() == null ? null : inst.getRoots().stream().findFirst().orElse(null);
        if (startId == null || inst.getNodeMap() == null || inst.getNodeMap().get(startId) == null) {
            throw new IllegalArgumentException("DSL 缺少可用的开始节点");
        }
        inst.setStarDate(new Date());
        inst.setState(Inst.STATE_RUNNING);
        instDao.save(inst);
        Task task = startTask.convert(inst.getNodeMap().get(startId), inst);
        taskDao.save(task);
        //mq发送开始运行
        taskProductor.sendTaskResult(task);
    }

    /**
     * 抢流程运行准入锁。
     * <p>
     * 命中已运行实例时删除刚创建的实例并抛 400「流程正在运行中」；
     * ZooKeeper 不可用时回退按库内状态校验，库内也没有运行中实例则放行（fail-open，避免锁组件拖垮主链路）。
     *
     * @return 是否真正持锁（ZK 降级放行时返回 false）
     */
    private boolean acquireRunLock(Inst inst, String planId) {
        if (!isRun(inst.getOptType()) || orchestration == null || !orchestration.isSingleRunningPerPlan()) {
            return false;
        }
        PlanRunLock.Result result = planRunLock.tryLock(planId, inst.getId());
        if (result.isAcquired()) {
            return true;
        }
        String runningInstId = result.isZkUnavailable()
                ? findOtherRunningInstId(planId, inst.getId()) : result.getOwnerInstId();
        if (result.isZkUnavailable() && runningInstId == null) {
            log.warn("ZooKeeper 不可用且库内无运行中实例，放行本次运行, planId={}, instId={}", planId, inst.getId());
            return false;
        }
        instDao.deleteById(inst.getId());
        throw new IllegalArgumentException("流程正在运行中（实例 " + runningInstId + "），请等待结束或取消后再运行");
    }

    private String findOtherRunningInstId(String planId, String selfId) {
        try {
            List<Inst> insts = instDao.findByPlanId(planId);
            if (insts == null) {
                return null;
            }
            return insts.stream()
                    .filter(i -> i != null && !StringUtils.isEmpty(i.getId()) && !i.getId().equals(selfId))
                    .filter(i -> Inst.STATE_RUNNING.equals(i.getState()) || Inst.STATE_WAITE.equals(i.getState()))
                    .map(Inst::getId)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("查询流程运行中实例失败, planId={}, msg={}", planId, e.getMessage());
            return null;
        }
    }

    public boolean isRun(String optType) {
        return optType != null && optType.equalsIgnoreCase("run");
    }

    private String firstHeader(Map<String, String> headers, String name) {
        if (headers == null || StringUtils.isEmpty(name)) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 启动结果：实例本体 + 是否命中幂等。
     */
    public static class RunResult {

        private final Inst inst;

        private final boolean idempotentHit;

        public RunResult(Inst inst, boolean idempotentHit) {
            this.inst = inst;
            this.idempotentHit = idempotentHit;
        }

        public Inst getInst() {
            return inst;
        }

        public boolean isIdempotentHit() {
            return idempotentHit;
        }
    }
}
