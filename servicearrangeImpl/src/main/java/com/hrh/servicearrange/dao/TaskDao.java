package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.Task;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Set;

/**
 * XXX 当出现循环线后  一个节点可能生成多个任务，一些接口需要进行调整
 */
public interface TaskDao extends MongoRepository<Task, String> {

    void deleteByInstIdAndNodeIdAndLoopTimes(String instId, String nodeId, String loopTimes);

    List<Task> findAllByInstIdAndNodeIdIn(String instId, Set<String> nodeIds);

    Task findByInstIdAndNodeIdAndLoopTimes(String instId, String nodeId, String loopTimes);

    @Query(value = "{ 'instId' : ?0 ,'type' : ?1 }")
    Task findStartTask(String instId, String type);
}
