package com.hrh.servicearrange.service;


import com.hrh.servicearrange.dsl.EdgeDecisionCell;
import com.hrh.servicearrange.dsl.FunTransform2ObjCell;
import com.hrh.servicearrange.entity.Task;

import java.util.List;

public interface ITaskService {
    String SERVICE_BEAN_NAME = "taskService";

    Boolean edgeDecisionDo(String startId, String instId, String exp, String[] R, String[] L, List<EdgeDecisionCell.ConditionData> conditionData, Task task);

    Boolean edgeLoopDecisionDo(String startId, String instId, String exp, String[] r, String[] l,Task task);
}
