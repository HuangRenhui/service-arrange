package com.hrh.servicearrange.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * 受限表达式求值器。
 * <p>
 * 决策线的 R/L 条件由 DSL 提供，历史上直接交给 JS 引擎（{@code ScriptUtil.eval}）执行：
 * JS 引擎可访问 Java 类型、可写死循环，等于把「执行任意代码 + 占死消费线程」的能力开放给 DSL 编辑者。
 * 这里改用 SpEL 的 {@link SimpleEvaluationContext}：
 * <ul>
 *     <li>只支持运算符、字面量、属性读取，<b>不支持</b> {@code T(...)} 类型引用、构造器、方法调用；</li>
 *     <li>SpEL 没有循环语法，不存在死循环；</li>
 *     <li>仍保留 {@code && || ! + - * / %}、三元与字符串比较，常见决策表达式可原样使用。</li>
 * </ul>
 * 求值失败不再静默返回 false 而不留痕，统一打 WARN 日志。
 */
@Slf4j
public final class SafeExpressionEvaluator {

    /**
     * 表达式长度上限，防御超长表达式拖慢解析
     */
    private static final int MAX_LENGTH = 1000;

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private static final SimpleEvaluationContext CONTEXT = SimpleEvaluationContext.forReadOnlyDataBinding().build();

    private SafeExpressionEvaluator() {
    }

    /**
     * 求值表达式，失败返回 null（调用方自行决定兜底值）
     */
    public static Object eval(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            log.warn("表达式超长（{} > {}），拒绝求值", trimmed.length(), MAX_LENGTH);
            return null;
        }
        //前端/DSL 习惯写 === / !==，SpEL 只接受 == / !=
        String normalized = trimmed.replace("===", "==").replace("!==", "!=");
        try {
            return PARSER.parseExpression(normalized).getValue(CONTEXT);
        } catch (Exception e) {
            log.warn("表达式求值失败，按兜底值处理, exp={}, msg={}", normalized, e.getMessage());
            return null;
        }
    }

    /**
     * 求布尔值，非布尔结果或异常时返回兜底值
     */
    public static boolean evalBoolean(String expression, boolean defaultValue) {
        Object value = eval(expression);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    /**
     * SpEL 单引号字符串字面量中，引号需要写成两个单引号
     */
    public static String escapeSingleQuote(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
