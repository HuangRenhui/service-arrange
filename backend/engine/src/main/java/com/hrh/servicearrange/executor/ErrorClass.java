package com.hrh.servicearrange.executor;

/**
 * 调用结果的三态分类，由 {@link ErrorClassifier} 依据
 * {@code task.error-mapping.*} 配置产出。
 * <p>
 * 与既有重试机制的衔接：
 * <ul>
 *     <li>{@link #SUCCESS}：维持成功路径；</li>
 *     <li>{@link #RETRYABLE}：走 {@code Task.RetryRules} 重试；</li>
 *     <li>{@link #FATAL}：跳过重试，直接置 FAIL 并触发 {@code edge_compensate} 补偿分支。</li>
 * </ul>
 */
public enum ErrorClass {

    /**
     * 调用成功。
     */
    SUCCESS,

    /**
     * 可重试错误：限流、网关 5xx、连接超时、数据库死锁等。
     */
    RETRYABLE,

    /**
     * 不可重试错误：参数错误、鉴权失败、业务拒绝等，重试无意义。
     */
    FATAL
}
