package com.hrh.servicearrange.controller;

import com.hrh.servicearrange.dao.PlanDao;
import com.hrh.servicearrange.entity.Plan;
import com.hrh.servicearrange.vo.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/plan")
public class PlanController {

    @Autowired
    private PlanDao planDao;

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Plan plan) {
        Date now = new Date();
        if (StringUtils.isEmpty(plan.getId())) {
            plan.setCreateDate(now);
        } else {
            planDao.findById(plan.getId()).ifPresent(old -> {
                if (plan.getCreateDate() == null) {
                    plan.setCreateDate(old.getCreateDate());
                }
                if (plan.getVersion() == null) {
                    plan.setVersion(old.getVersion() == null ? 1 : old.getVersion() + 1);
                }
            });
        }
        plan.setModifyDate(now);
        if (plan.getVersion() == null) {
            plan.setVersion(1);
        }
        if (StringUtils.isEmpty(plan.getStatus())) {
            plan.setStatus(Plan.STATUS_DRAFT);
        }
        return ApiResponse.ok(planDao.save(plan));
    }

    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestParam String id) {
        Plan plan = planDao.findById(id).orElseThrow(() -> new IllegalArgumentException("流程不存在"));
        if (StringUtils.isEmpty(plan.getDsl())) {
            throw new IllegalArgumentException("空 DSL 不能发布");
        }
        plan.setStatus(Plan.STATUS_PUBLISHED);
        plan.setVersion(plan.getVersion() == null ? 1 : plan.getVersion() + 1);
        plan.setModifyDate(new Date());
        return ApiResponse.ok(planDao.save(plan));
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(@RequestParam String id) {
        Plan plan = planDao.findById(id).orElseThrow(() -> new IllegalArgumentException("流程不存在"));
        plan.setStatus(Plan.STATUS_DISABLED);
        plan.setModifyDate(new Date());
        return ApiResponse.ok(planDao.save(plan));
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody List<String> ids) {
        if (ids != null) {
            ids.forEach(id -> planDao.deleteById(id));
        }
        return ApiResponse.ok("success");
    }

    @GetMapping("/findById")
    public Map<String, Object> findById(@RequestParam String id) {
        return ApiResponse.ok(planDao.findById(id).orElse(null));
    }

    @GetMapping("/list")
    public Map<String, Object> list() {
        return ApiResponse.ok(planDao.findAll());
    }
}
