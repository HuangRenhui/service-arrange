package com.hrh.servicearrange.service;

import com.hrh.servicearrange.dao.InstDao;
import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.lock.PlanRunLock;
import com.hrh.servicearrange.vo.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实例级联删除：实例记录、任务、日志、本地文件一并清理。
 * <p>
 * 运行中的实例默认拒绝删除，避免在途节点继续写入已删除的实例；确需强制删除时传 {@code force=true}。
 * 库记录先删、文件后删：文件删除失败只会残留孤儿文件，不会留下「有实例无文件」的脏数据。
 *
 * @author huangrenhui
 */
@Slf4j
@Service
public class InstDeleteService {

    @Autowired
    private InstDao instDao;
    @Autowired
    private TaskDao taskDao;
    @Autowired
    private InstLogDao instLogDao;
    @Autowired
    private PlanRunLock planRunLock;
    @Autowired
    private InstFileService instFileService;

    /**
     * 批量删除实例及其级联数据。
     *
     * @param ids   实例 id 列表
     * @param force 是否允许删除运行中的实例
     * @return 删除统计
     */
    public Map<String, Object> delete(List<String> ids, boolean force) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("请指定要删除的实例 id");
        }
        int instCount = 0;
        long taskCount = 0;
        long logCount = 0;
        int fileCount = 0;
        for (String id : ids) {
            if (StringUtils.isEmpty(id)) {
                continue;
            }
            Inst inst = instDao.findById(id).orElse(null);
            if (inst == null) {
                log.debug("inst not found, skip delete, id={}", id);
                continue;
            }
            if (Inst.STATE_RUNNING.equals(inst.getState()) && !force) {
                throw new IllegalArgumentException("实例正在运行，不能删除（可先取消，或传 force=true）：" + id);
            }
            taskCount += taskDao.deleteByInstId(id);
            logCount += instLogDao.deleteByInstId(id);
            instDao.deleteById(id);
            //实例删除后释放流程准入锁（属主已不存在，避免下次运行被陈旧锁拦住）
            planRunLock.release(inst.getPlanId(), id);
            instCount++;
            fileCount += instFileService.deleteByInstId(id);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instCount", instCount);
        data.put("taskCount", taskCount);
        data.put("logCount", logCount);
        data.put("fileCount", fileCount);
        return ApiResponse.ok(data);
    }
}
