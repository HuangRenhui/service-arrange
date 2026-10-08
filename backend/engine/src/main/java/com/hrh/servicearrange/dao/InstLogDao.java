package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.InstLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface InstLogDao extends MongoRepository<InstLog, String> {
    List<InstLog> findByInstIdOrderByCreateDateAsc(String instId);
    List<InstLog> findByInstIdAndNodeIdOrderByCreateDateAsc(String instId, String nodeId);
}
