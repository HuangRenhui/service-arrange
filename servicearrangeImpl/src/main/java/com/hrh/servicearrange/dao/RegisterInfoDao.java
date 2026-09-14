package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.RegisterInfoEntity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * @author huangrenhui
 * @date 2021/12/7
 * @flow
 */
public interface RegisterInfoDao extends MongoRepository<RegisterInfoEntity, String> {
    void deleteAllByIdIn(List<String> idList);

}
