package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.InstLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface InstLogDao extends MongoRepository<InstLog, String> {
    List<InstLog> findByInstIdOrderByCreateDateAsc(String instId);
    List<InstLog> findByInstIdAndNodeIdOrderByCreateDateAsc(String instId, String nodeId);

    /**
     * 删除实例下的全部日志（实例级联删除用）
     *
     * @return 删除的日志数
     */
    long deleteByInstId(String instId);
}
