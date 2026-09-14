package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.InstLog;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InstLogDao extends MongoRepository<InstLog, String> {

}
