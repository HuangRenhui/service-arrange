package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.Inst;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface InstDao extends MongoRepository<Inst, String> {

    @Query(value = "{ 'id' : ?0 }", fields = "{ 'state' : 1}")
    Inst findStateById(String id);

    @Query(value = "{ 'id' : ?0 }", fields = "{ 'sync' : 1}")
    Inst findSyncById(String id);


    @Query(value = "{ 'id' : ?0 }", fields = "{ 'outputs' : 1}")
    Inst findOutputsById(String id);
}
