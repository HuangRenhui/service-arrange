package com.hrh.servicearrange.controller;

import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.lock.PlanRunLock;
import com.hrh.servicearrange.mq.TaskProductor;
import com.hrh.servicearrange.service.InstDeleteService;
import com.hrh.servicearrange.vo.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/inst")
public class InstQueryController {

    @Autowired
    private InstDao instDao;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private InstLogDao instLogDao;
    @Autowired
    private TaskProductor taskProductor;
    @Autowired
    private PlanRunLock planRunLock;
    @Autowired
    private InstDeleteService instDeleteService;

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(required = false) String planId,
                                    @RequestParam(required = false) String state) {
        List<Inst> all = StringUtils.isEmpty(planId) ? instDao.findAll() : instDao.findByPlanId(planId);
        if (!StringUtils.isEmpty(state)) {
            all = all.stream().filter(i -> state.equals(i.getState())).collect(Collectors.toList());
        }
        return ApiResponse.ok(all);
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam String id) {
        Inst inst = instDao.findById(id).orElse(null);
        if (inst == null) {
            throw new IllegalArgumentException("实例不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("inst", inst);
        data.put("tasks", taskDao.findByInstId(id));
        data.put("logs", instLogDao.findByInstIdOrderByCreateDateAsc(id));
        return ApiResponse.ok(data);
    }

    /**
     * 挂起实例：只置状态，不打断在途节点。
     * <p>
     * 下一个节点被消费时会检测到 SUSPEND，把「节点名|循环次数」记入 {@code suspendNodes} 后 ack，
     * 不再继续调度下游。
     *
     * @param id 实例 id
     * @return 更新后的实例
     */
    @PostMapping("/suspend")
    public Map<String, Object> suspend(@RequestParam String id) {
        Inst inst = instDao.findById(id).orElseThrow(() -> new IllegalArgumentException("实例不存在"));
        if (!Inst.STATE_RUNNING.equals(inst.getState()) && !Inst.STATE_WAITE.equals(inst.getState())) {
            throw new IllegalArgumentException("仅运行中或等待中的实例可以挂起");
        }
        inst.setState(Inst.STATE_SUSPEND);
        inst.setModifyDate(new java.util.Date());
        instDao.save(inst);
        return ApiResponse.ok(inst);
    }

    /**
     * 恢复挂起实例。
     * <p>
     * {@code suspendNodes} 记录的是「已执行完但下游被拦下」的节点，恢复时必须重投其**结果**
     * （继续调度下游），而不是重投执行，否则会造成节点重复执行与副作用重复。
     *
     * @param id 实例 id
     * @return 更新后的实例
     */
    @PostMapping("/resume")
    public Map<String, Object> resume(@RequestParam String id) {
        Inst inst = instDao.findById(id).orElseThrow(() -> new IllegalArgumentException("实例不存在"));
        if (!Inst.STATE_SUSPEND.equals(inst.getState())) {
            throw new IllegalArgumentException("仅挂起实例可以恢复");
        }
        Set<String> suspended = inst.getSuspendNodes() == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(inst.getSuspendNodes());
        inst.setSuspendNodes(new LinkedHashSet<>());
        inst.setState(Inst.STATE_RUNNING);
        inst.setModifyDate(new java.util.Date());
        instDao.save(inst);

        if (suspended.isEmpty()) {
            //挂起发生在调度间隙：没有待继续的节点，置回运行中即可
            return ApiResponse.ok(inst);
        }
        List<Task> tasks = taskDao.findByInstId(id);
        int resumed = 0;
        for (String item : suspended) {
            String[] parts = item.split("\\|", -1);
            String nodeName = parts[0];
            String loopTimes = parts.length > 1 ? parts[1] : "0";
            Task matched = tasks.stream()
                    .filter(t -> nodeName.equals(t.getNodeName()) && loopTimes.equals(t.getLoopTimes()))
                    .findFirst().orElse(null);
            if (matched == null) {
                continue;
            }
            //节点已执行完毕，重投结果是继续调度下游；重投执行会导致副作用重复
            taskProductor.sendTaskResult(matched);
            resumed++;
        }
        if (resumed == 0) {
            throw new IllegalStateException("挂起节点未找到对应任务，无法恢复：" + suspended);
        }
        return ApiResponse.ok(inst);
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam String id) {
        Inst inst = instDao.findById(id).orElseThrow(() -> new IllegalArgumentException("实例不存在"));
        if (Inst.STATE_SUCCESS.equals(inst.getState()) || Inst.STATE_FAIL.equals(inst.getState())
                || Inst.STATE_CANCEL.equals(inst.getState())) {
            throw new IllegalArgumentException("终态实例不能取消");
        }
        inst.setState(Inst.STATE_CANCEL);
        inst.setEndDate(new java.util.Date());
        inst.setFailReason("cancelled");
        instDao.save(inst);
        //取消即终态：立即释放流程准入锁，允许同流程再次运行
        planRunLock.release(inst.getPlanId(), id);
        return ApiResponse.ok(inst);
    }

    /**
     * 删除实例（级联）：实例记录、任务、日志、本地文件一并清理。
     * <p>
     * 运行中的实例默认拒绝删除，避免在途节点继续写入已删除的实例；确需强制删除时传 {@code force=true}。
     *
     * @param ids   实例 id 列表
     * @param force 是否允许删除运行中的实例
     * @return 删除统计
     */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody List<String> ids,
                                      @RequestParam(required = false, defaultValue = "false") boolean force) {
        return instDeleteService.delete(ids, force);
    }
}
