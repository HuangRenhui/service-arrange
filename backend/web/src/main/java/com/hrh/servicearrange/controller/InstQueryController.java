package com.hrh.servicearrange.controller;

import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.mq.TaskProductor;
import com.hrh.servicearrange.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @PostMapping("/resume")
    public Map<String, Object> resume(@RequestParam String id) {
        Inst inst = instDao.findById(id).orElseThrow(() -> new IllegalArgumentException("实例不存在"));
        if (!Inst.STATE_SUSPEND.equals(inst.getState())) {
            throw new IllegalArgumentException("仅挂起实例可以恢复");
        }
        inst.setState(Inst.STATE_RUNNING);
        instDao.save(inst);
        List<Task> tasks = taskDao.findByInstId(id);
        tasks.stream()
                .filter(t -> Task.STATE_WAITE.equals(t.getState()) || Task.STATE_RUNNING.equals(t.getState()))
                .findFirst()
                .ifPresent(taskProductor::sendTaskResult);
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
        return ApiResponse.ok(inst);
    }
}
