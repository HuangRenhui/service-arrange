package com.hrh.servicearrange.controller;

import com.hrh.servicearrange.config.TaskDispatchProperties;
import com.hrh.servicearrange.mq.MqAvailability;
import com.hrh.servicearrange.mq.TaskDispatcher;
import com.hrh.servicearrange.vo.ApiResponse;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final MongoTemplate mongoTemplate;
    private final TaskDispatchProperties dispatchProperties;
    private final MqAvailability mqAvailability;
    private final TaskDispatcher taskDispatcher;

    public HealthController(MongoTemplate mongoTemplate,
                            TaskDispatchProperties dispatchProperties,
                            MqAvailability mqAvailability,
                            TaskDispatcher taskDispatcher) {
        this.mongoTemplate = mongoTemplate;
        this.dispatchProperties = dispatchProperties;
        this.mqAvailability = mqAvailability;
        this.taskDispatcher = taskDispatcher;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("mongo", probeMongo());
        data.put("dispatch", dispatchInfo());
        return ApiResponse.ok(data);
    }

    /**
     * 任务投递状态：配置模式 + 实际通道 + MQ 可达性，便于确认是否已降级为本地直连
     */
    private Map<String, Object> dispatchInfo() {
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("mode", dispatchProperties.normalizedMode());
        dispatch.put("channel", taskDispatcher.currentChannel());
        if (dispatchProperties.needProbe()) {
            dispatch.put("mqAvailable", mqAvailability.isAvailable());
            dispatch.put("mqLastError", mqAvailability.getLastError());
        }
        return dispatch;
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
