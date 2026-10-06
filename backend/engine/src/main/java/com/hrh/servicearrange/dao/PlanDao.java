package com.hrh.servicearrange.dao;

import com.hrh.servicearrange.entity.Plan;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PlanDao extends MongoRepository<Plan, String> {
}
