package com.hrh.servicearrange.executor.impl;

import com.hrh.servicearrange.dao.InstLogDao;
import com.hrh.servicearrange.dao.TaskDao;
import com.hrh.servicearrange.entity.Inst;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.executor.Execute;
import com.hrh.servicearrange.parser.annotation.CellType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service(CellType.FUN_COMPENSATE_DATAMAP)
public class CompensateDatamapExecutor implements Execute {
    @Autowired
    private DataMapOperateExecutor dataMapOperateExecutor;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return dataMapOperateExecutor.runProcess(task, inst, taskDao, instLogDao);
    }
}

@Service(CellType.OPERATOR_HTTP_COMPENSATE)
class HttpCompensateExecutor implements Execute {
    @Autowired
    private HttpTaskExecutor httpTaskExecutor;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return httpTaskExecutor.runProcess(task, inst, taskDao, instLogDao);
    }
}

@Service(CellType.OPERATOR_WEBSERVICE_COMPENSATE)
class WebServiceCompensateExecutor implements Execute {
    @Autowired
    private WebServiceOperateExecutor webServiceOperateExecutor;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return webServiceOperateExecutor.runProcess(task, inst, taskDao, instLogDao);
    }
}

@Service(CellType.OPERATOR_DUBBO_COMPENSATE)
class DubboCompensateExecutor implements Execute {
    @Autowired
    private DubboOperateExecutor dubboOperateExecutor;

    @Override
    public Task runProcess(Task task, Inst inst, TaskDao taskDao, InstLogDao instLogDao) {
        return dubboOperateExecutor.runProcess(task, inst, taskDao, instLogDao);
    }
}
