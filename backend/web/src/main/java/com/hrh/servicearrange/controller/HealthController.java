package com.hrh.servicearrange.controller;

import com.hrh.servicearrange.vo.ApiResponse;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final MongoTemplate mongoTemplate;

    public HealthController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("mongo", probeMongo());
        return ApiResponse.ok(data);
    }

    private String probeMongo() {
        try {
            mongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
