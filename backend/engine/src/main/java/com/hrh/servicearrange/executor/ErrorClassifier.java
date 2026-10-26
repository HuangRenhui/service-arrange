package com.hrh.servicearrange.executor;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hrh.servicearrange.config.ErrorMappingProperties;
import com.hrh.servicearrange.entity.Task;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 错误分类器：把各协议的「状态码 / 响应体业务码 / SOAP Fault / SQLState / 退出码 / 异常」
 * 依据 {@code task.error-mapping.*} 归一化为 {@link ErrorClass} 三态。
 * <p>
 * 判定顺序：
 * <ol>
 *     <li>按协议配置的 {@code match} 提取错误码；</li>
 *     <li>依次匹配 success → retryable → fatal 三张列表；</li>
 *     <li>主判据为成功且配置了 {@code body-code} 时，再用业务码做二级判定（HTTP 200 + code≠0 形态）；</li>
 *     <li>仍无法判定时，无错误码的异常按 {@code retryable-exceptions} 前缀匹配，
 *         其余按 {@code default-action} 兜底（保守 → FATAL，乐观 → RETRYABLE）。</li>
 * </ol>
 * 设计说明见《配置说明》§3.3.3。
 */
@Component
public class ErrorClassifier {

    /**
     * SOAP Fault 错误码提取，兼容带命名空间前缀的 faultcode 节点。
     */
    private static final Pattern SOAP_FAULT_CODE = Pattern.compile(
            "<(?:[\\w.-]+:)?faultcode[^>]*>(.*?)</(?:[\\w.-]+:)?faultcode>", Pattern.DOTALL);

    private final ErrorMappingProperties props;

    public ErrorClassifier(ErrorMappingProperties props) {
        this.props = props;
    }

    /**
     * 对一次调用结果做三态分类。
     *
     * @param protocol 协议名：http / dubbo / webservice / sql / script
     * @param status   主判据数值：HTTP 状态码、脚本退出码，无则为 null
     * @param body     响应体，用于 body-jsonpath / soap-fault
     * @param ex       调用异常，用于 sqlstate 与异常前缀兜底，无则为 null
     * @return 分类结果（含归一化错误码与描述）
     */
    public Verdict classify(String protocol, Integer status, String body, Throwable ex) {
        return classify(null, protocol, status, body, ex);
    }

    /**
     * 对一次调用结果做三态分类，并叠加节点级覆盖。
     * <p>
     * 节点 {@code data.errorMapping} 与全局 {@code task.error-mapping.protocols.<protocol>} 同构，
     * 采用「节点表优先、全局表兜底」的两层判定：节点只声明差异码即可，未声明的码仍按全局规则处理。
     *
     * @param task     当前任务，用于读取节点级覆盖；为 null 时只用全局配置
     * @param protocol 协议名：http / dubbo / webservice / sql / script
     * @param status   主判据数值：HTTP 状态码、脚本退出码，无则为 null
     * @param body     响应体，用于 body-jsonpath / soap-fault
     * @param ex       调用异常，用于 sqlstate 与异常前缀兜底，无则为 null
     * @return 分类结果（含归一化错误码与描述）
     */
    public Verdict classify(Task task, String protocol, Integer status, String body, Throwable ex) {
        ErrorMappingProperties.ProtocolRule base = props.rule(protocol);
        ErrorMappingProperties.ProtocolRule override = nodeOverride(task);
        if (base == null && override == null) {
            return fallback(errCode(status), ex);
        }

        String code = extract(pickMatch(base, override), pickPath(base, override), status, body, ex);
        ErrorClass primary = matchBoth(base, override, code);

        // 主判据成功时，再看业务码：兼容 HTTP 200 但 body.code 非 0 的厂商形态
        if (primary == ErrorClass.SUCCESS) {
            Verdict biz = judgeBodyCode(override, body, protocol, ex);
            if (biz == null) {
                biz = judgeBodyCode(base, body, protocol, ex);
            }
            return biz != null ? biz : new Verdict(ErrorClass.SUCCESS, code, null);
        }

        if (primary != null) {
            return new Verdict(primary, code, describe(protocol, code, ex));
        }
        return fallback(code, ex);
    }

    /**
     * 便捷重载：仅 HTTP 状态码场景。
     *
     * @param protocol 协议名
     * @param status   状态码
     * @param body     响应体
     * @return 分类结果
     */
    public Verdict classify(String protocol, int status, String body) {
        return classify(protocol, Integer.valueOf(status), body, null);
    }

    /**
     * 便捷重载：仅异常场景。
     *
     * @param protocol 协议名
     * @param ex       异常
     * @return 分类结果
     */
    public Verdict classify(String protocol, Throwable ex) {
        return classify(protocol, null, null, ex);
    }

    /**
     * 便捷重载：仅异常场景，并叠加节点级覆盖。
     *
     * @param task     当前任务
     * @param protocol 协议名
     * @param ex       异常
     * @return 分类结果
     */
    public Verdict classify(Task task, String protocol, Throwable ex) {
        return classify(task, protocol, null, null, ex);
    }

    /**
     * 码表两层匹配：节点表命中即定论，未命中再查全局表。
     */
    private ErrorClass matchBoth(ErrorMappingProperties.ProtocolRule base,
                                 ErrorMappingProperties.ProtocolRule override, String code) {
        if (override != null) {
            ErrorClass hit = matchThree(override.getSuccess(), override.getRetryable(),
                    override.getFatal(), code);
            if (hit != null) {
                return hit;
            }
        }
        if (base == null) {
            return null;
        }
        return matchThree(base.getSuccess(), base.getRetryable(), base.getFatal(), code);
    }

    /**
     * 业务码二级判定。
     *
     * @return 命中或有业务码但未配置时返回定论；未配置 body-code 或取不到业务码时返回 null，交给下一层
     */
    private Verdict judgeBodyCode(ErrorMappingProperties.ProtocolRule rule, String body,
                                  String protocol, Throwable ex) {
        if (rule == null || rule.getBodyCode() == null || !hasText(rule.getBodyCode().getPath())) {
            return null;
        }
        ErrorMappingProperties.BodyCode bc = rule.getBodyCode();
        String bizCode = jsonpath(body, bc.getPath());
        if (bizCode == null) {
            return null;
        }
        ErrorClass hit = matchThree(bc.getSuccess(), bc.getRetryable(), bc.getFatal(), bizCode);
        return new Verdict(hit == null ? defaultClass() : hit, bizCode, describe(protocol, bizCode, ex));
    }

    /**
     * 取判据方式：节点优先，其次全局，最后默认 status。
     */
    private String pickMatch(ErrorMappingProperties.ProtocolRule base,
                             ErrorMappingProperties.ProtocolRule override) {
        if (override != null && hasText(override.getMatch())) {
            return override.getMatch();
        }
        if (base != null && hasText(base.getMatch())) {
            return base.getMatch();
        }
        return "status";
    }

    /**
     * 取业务码 jsonpath：节点 path 优先，其次全局 path，再退到各自的 body-code path。
     */
    private String pickPath(ErrorMappingProperties.ProtocolRule base,
                            ErrorMappingProperties.ProtocolRule override) {
        if (override != null && hasText(override.getPath())) {
            return override.getPath();
        }
        if (base != null && hasText(base.getPath())) {
            return base.getPath();
        }
        if (override != null && override.getBodyCode() != null && hasText(override.getBodyCode().getPath())) {
            return override.getBodyCode().getPath();
        }
        if (base != null && base.getBodyCode() != null && hasText(base.getBodyCode().getPath())) {
            return base.getBodyCode().getPath();
        }
        return null;
    }

    /**
     * 从节点入参中取 {@code errorMapping}，未配置或结构非法时返回 null（不影响全局配置）。
     * <p>
     * 手工解析而非 {@code JSONUtil.toBean}：DSL 里常见 {@code "success":[200,204]} 这类数字字面量，
     * toBean 到 {@code List<String>} 会保留 Integer 元素，后续按字符串比较即抛 ClassCastException。
     */
    private ErrorMappingProperties.ProtocolRule nodeOverride(Task task) {
        if (task == null || task.getInputs() == null || task.getInputs().isEmpty()) {
            return null;
        }
        try {
            JSONObject inputs = JSONUtil.parseObj(task.getInputs());
            JSONObject node = inputs.getJSONObject("errorMapping");
            if (node == null || node.isEmpty()) {
                return null;
            }
            ErrorMappingProperties.ProtocolRule rule = new ErrorMappingProperties.ProtocolRule();
            rule.setMatch(node.getStr("match"));
            rule.setPath(node.getStr("path"));
            rule.setSuccess(strList(node.getJSONArray("success")));
            rule.setRetryable(strList(node.getJSONArray("retryable")));
            rule.setFatal(strList(node.getJSONArray("fatal")));
            JSONObject bodyCode = node.getJSONObject("bodyCode");
            if (bodyCode != null && !bodyCode.isEmpty()) {
                ErrorMappingProperties.BodyCode bc = new ErrorMappingProperties.BodyCode();
                bc.setPath(bodyCode.getStr("path"));
                bc.setSuccess(strList(bodyCode.getJSONArray("success")));
                bc.setRetryable(strList(bodyCode.getJSONArray("retryable")));
                bc.setFatal(strList(bodyCode.getJSONArray("fatal")));
                rule.setBodyCode(bc);
            }
            return rule;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 JSON 数组统一转成字符串列表，兼容数字/布尔字面量。
     */
    private List<String> strList(JSONArray array) {
        List<String> list = new ArrayList<>();
        if (array == null || array.isEmpty()) {
            return list;
        }
        for (Object item : array) {
            if (item != null) {
                list.add(String.valueOf(item));
            }
        }
        return list;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 按 match 方式提取错误码。
     */
    private String extract(String match, String path, Integer status, String body, Throwable ex) {
        String mode = match == null ? "status" : match;
        switch (mode) {
            case "body-jsonpath": {
                String bizCode = jsonpath(body, path);
                //响应体取不到业务码时退回状态码，避免网关 5xx 返回非 JSON 时被误判为成功或丢失可重试语义
                return bizCode != null ? bizCode : errCode(status);
            }
            case "soap-fault":
                return regex(body, SOAP_FAULT_CODE);
            case "sqlstate":
                return sqlState(ex);
            case "exit-code":
            case "status":
            default:
                return errCode(status);
        }
    }

    /**
     * 三张列表依次匹配，全部未命中返回 null。
     */
    private ErrorClass matchThree(List<String> success, List<String> retryable,
                                  List<String> fatal, String code) {
        if (hit(success, code)) {
            return ErrorClass.SUCCESS;
        }
        if (hit(retryable, code)) {
            return ErrorClass.RETRYABLE;
        }
        if (hit(fatal, code)) {
            return ErrorClass.FATAL;
        }
        return null;
    }

    /**
     * 通配匹配：{@code 42xxx} / {@code 42*} 按前缀，其余按忽略大小写全等。
     */
    private boolean hit(List<String> patterns, String code) {
        if (patterns == null || patterns.isEmpty() || code == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            String p = pattern.trim();
            if (p.isEmpty()) {
                continue;
            }
            String prefix = null;
            if (p.endsWith("xxx")) {
                prefix = p.substring(0, p.length() - 3);
            } else if (p.endsWith("*")) {
                prefix = p.substring(0, p.length() - 1);
            }
            boolean matched = prefix != null ? code.startsWith(prefix) : p.equalsIgnoreCase(code);
            if (matched) {
                return true;
            }
        }
        return false;
    }

    /**
     * 无匹配规则时的兜底分类。
     */
    private Verdict fallback(String code, Throwable ex) {
        if (retryableException(ex)) {
            return new Verdict(ErrorClass.RETRYABLE, code, describe(null, code, ex));
        }
        return new Verdict(defaultClass(), code, describe(null, code, ex));
    }

    /**
     * 未知错误码的默认分类。
     */
    private ErrorClass defaultClass() {
        return props.getDefaultAction() == ErrorMappingProperties.DefaultAction.OPTIMISTIC
                ? ErrorClass.RETRYABLE : ErrorClass.FATAL;
    }

    /**
     * 异常类名前缀命中即视为可重试（连接超时、读取超时、瞬时 SQL 异常等）。
     */
    private boolean retryableException(Throwable ex) {
        if (ex == null) {
            return false;
        }
        List<String> prefixes = props.getRetryableExceptions();
        if (prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        Throwable current = ex;
        int depth = 0;
        // 沿 cause 链匹配，避免执行器包装异常后丢失类型语义
        while (current != null && depth++ < 10) {
            String name = current.getClass().getName();
            for (String prefix : prefixes) {
                if (prefix != null && !prefix.trim().isEmpty() && name.startsWith(prefix.trim())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 沿异常链取第一个非空 SQLState。
     */
    private String sqlState(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof SQLException) {
                String state = ((SQLException) current).getSQLState();
                if (state != null && !state.isEmpty()) {
                    return state;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 用 jsonpath 从响应体取业务码，取不到或非 JSON 时返回 null。
     */
    private String jsonpath(String body, String path) {
        if (body == null || path == null || path.isEmpty()) {
            return null;
        }
        try {
            JSON json = JSONUtil.parse(body);
            Object value = JSONUtil.getByPath(json, path);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 正则取第一个分组，未匹配返回 null。
     */
    private String regex(String text, Pattern pattern) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String code = matcher.group(1);
            return code == null ? null : code.trim();
        }
        return null;
    }

    private String errCode(Integer status) {
        return status == null ? null : String.valueOf(status);
    }

    /**
     * 归一化错误描述，便于写入日志与 InstLog。
     */
    private String describe(String protocol, String code, Throwable ex) {
        StringBuilder sb = new StringBuilder();
        if (protocol != null) {
            sb.append("protocol=").append(protocol);
        }
        if (code != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("code=").append(code);
        }
        if (ex != null) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("exception=").append(ex.getClass().getSimpleName())
                    .append(": ").append(ex.getMessage());
        }
        return sb.toString();
    }

    /**
     * 分类结果。
     */
    public static class Verdict {

        private final ErrorClass errorClass;
        private final String code;
        private final String message;

        public Verdict(ErrorClass errorClass, String code, String message) {
            this.errorClass = errorClass;
            this.code = code;
            this.message = message;
        }

        public ErrorClass getErrorClass() {
            return errorClass;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public boolean isSuccess() {
            return errorClass == ErrorClass.SUCCESS;
        }
    }
}
