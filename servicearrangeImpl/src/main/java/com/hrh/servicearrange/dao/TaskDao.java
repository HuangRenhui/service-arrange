package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.Task;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * XXX 当出现循环线后  一个节点可能生成多个任务，一些接口需要进行调整
 */
public interface TaskDao extends MongoRepository<Task, String> {

    void deleteByInstIdAndNodeIdAndLoopTimes(String instId, String nodeId, String loopTimes);
}
