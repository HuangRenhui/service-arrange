package com.hrh.servicearrange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 编排治理开关：发布门禁、任务超时、业务重试退避上限。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "task.orchestration")
public class OrchestrationProperties {

    /**
     * 为 true 时只允许运行 status=PUBLISHED 的 Plan；本地调试 classpath DSL 请保持 false。
     */
    private boolean requirePublished = false;

    /**
     * 单任务默认超时（毫秒）。节点 RetryRules.timeoutMs 优先。
     */
    private long taskTimeoutMs = 60000L;

    /**
     * 任务失败后同步重试的最大休眠（毫秒），避免消费线程被长 delay 占满。
     */
    private long retryDelayMaxMs = 3000L;

    /**
     * 同一流程（planId）是否只允许一个运行中实例。
     * <p>
     * 开启后 {@code /inst/run} 会先抢 ZooKeeper 准入锁 {@code /PLAN_RUNNING_{planId}}，
     * 已有实例在跑时直接返回 400「流程正在运行中」，避免同一流程被重复点击/重复提交而并行执行。
     */
    private boolean singleRunningPerPlan = true;
}
