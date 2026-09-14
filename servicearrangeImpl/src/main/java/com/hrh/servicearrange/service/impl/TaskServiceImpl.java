package com.hrh.servicearrange.service.impl;

import com.hrh.servicearrange.dsl.FunTransform2ObjCell;
import com.hrh.servicearrange.entity.Task;
import com.hrh.servicearrange.service.ITaskService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author huangrenhui
 * @date 2022/4/8
 * @flow
 */
@Service(ITaskService.SERVICE_BEAN_NAME)
public class TaskServiceImpl implements ITaskService {
    @Override
    public Boolean edgeDecisionDo(String startId, String instId, String exp, String[] R, String[] L, Task task) {
        return null;
    }

    @Override
    public Boolean edgeLoopDecisionDo(String startId, String instId, String exp, String[] r, String[] l, List<FunTransform2ObjCell.Data.TransformRule> transformRules, Task task) {
        return null;
    }
}
