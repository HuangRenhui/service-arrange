package com.hrh.servicearrange.mq;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * MQ 监听装配条件：仅当 {@code task.dispatch.mode} 为 {@code mq} 或 {@code auto} 时装配。
 * <p>
 * {@code local} 模式下一个 {@code @EnableBinding} 都不注册，从根上避免创建 RabbitMQ / binder 连接。
 * 这里直接读 Environment 而不依赖注解占位符解析，避免不同 Spring 版本下的解析差异。
 */
public class OnMqDispatchCondition implements Condition {

    private static final String MODE_KEY = "task.dispatch.mode";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        String mode = environment == null ? "auto" : environment.getProperty(MODE_KEY, "auto");
        String normalized = mode == null ? "auto" : mode.trim().toLowerCase();
        return "mq".equals(normalized) || "auto".equals(normalized);
    }
}
