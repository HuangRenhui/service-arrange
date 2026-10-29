package com.hrh.servicearrange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错误码映射与重试策略配置。
 * <p>
 * 目标：换协议 / 换厂商时只改映射配置，执行器、编排、重试、补偿逻辑零改动。
 * 每个协议（http / dubbo / webservice / sql / script）通过 {@code match} 指定错误码提取方式，
 * 再由 {@code success / retryable / fatal} 三张列表把错误码映射为三态分类：
 * <ul>
 *     <li>{@code SUCCESS}：调用成功；</li>
 *     <li>{@code RETRYABLE}：可重试错误，走既有 {@code Task.RetryRules}；</li>
 *     <li>{@code FATAL}：不可重试错误，跳过重试直接失败并触发补偿分支。</li>
 * </ul>
 * 配置结构与取值说明见《配置说明》§3.3.3。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "task.error-mapping")
public class ErrorMappingProperties {

    /**
     * 未知错误码的默认分类策略。
     */
    public enum DefaultAction {
        /**
         * 保守：未知错误码按不可重试处理，直接失败转补偿或人工。
         * 适用于扣款、下单等有副作用的调用。
         */
        CONSERVATIVE,
        /**
         * 乐观：未知错误码按可重试处理。
         * 适用于查询类、幂等类调用。
         */
        OPTIMISTIC
    }

    /**
     * 未知错误码的默认分类，默认保守。
     */
    private DefaultAction defaultAction = DefaultAction.CONSERVATIVE;

    /**
     * 需要按可重试处理的异常类名前缀（匹配完整类名，含子类）。
     * <p>
     * 用于「无错误码」的场景：连接超时、读取超时、连接重置等网络异常天然可重试，
     * 若不显式声明会被 {@link #defaultAction} 判为 FATAL，造成重试能力倒退。
     */
    private List<String> retryableExceptions = new ArrayList<>();

    /**
     * 各协议的错误码映射规则，key 为协议名（http / dubbo / webservice / sql / script，忽略大小写）。
     */
    private Map<String, ProtocolRule> protocols = new LinkedHashMap<>();

    /**
     * 取协议规则，未配置时返回 null（调用方走默认分类）。
     *
     * @param protocol 协议名
     * @return 协议规则，可能为 null
     */
    public ProtocolRule rule(String protocol) {
        if (protocols == null || protocols.isEmpty() || protocol == null) {
            return null;
        }
        return protocols.get(protocol.toLowerCase());
    }

    /**
     * 单个协议的错误码映射规则。
     */
    @Getter
    @Setter
    public static class ProtocolRule {

        /**
         * 错误码提取方式：status / body-jsonpath / soap-fault / sqlstate / exit-code。
         */
        private String match = "status";

        /**
         * 判定为成功的错误码集合。
         */
        private List<String> success = new ArrayList<>();

        /**
         * 判定为可重试的错误码集合。
         */
        private List<String> retryable = new ArrayList<>();

        /**
         * 判定为不可重试的错误码集合。
         */
        private List<String> fatal = new ArrayList<>();

        /**
         * 本协议下「未命中三张列表的错误码」的默认分类，为 null 时继承全局 {@code task.error-mapping.default-action}。
         * <p>
         * 用于按协议区分语义：脚本退出码、查询类调用未声明的码通常可重试（optimistic），
         * 而有副作用的写操作应保持 conservative。显式写在 retryable / fatal 里的码仍优先于此项。
         */
        private DefaultAction defaultAction;

        /**
         * 主判据为 {@code body-jsonpath} 时的 jsonpath 表达式，例如 {@code $.errorCode}。
         */
        private String path;

        /**
         * 业务码二级判定：HTTP 200 / 2xx 但响应体中业务码非成功的主流厂商形态。
         */
        private BodyCode bodyCode;
    }

    /**
     * 响应体业务码映射（仅在主判据判定为成功时才生效）。
     */
    @Getter
    @Setter
    public static class BodyCode {

        /**
         * 业务码的 jsonpath 表达式，例如 {@code $.code}。
         */
        private String path;

        private List<String> success = new ArrayList<>();

        private List<String> retryable = new ArrayList<>();

        private List<String> fatal = new ArrayList<>();
    }
}
