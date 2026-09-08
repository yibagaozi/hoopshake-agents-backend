package com.cnsportiot.cloud.harness.llm;

import java.util.Locale;

/**
 * 瞬时错误判定 降级链第1层:超时 / 429 / 5xx / 连接重置 视为可重试可降级,
 * 其余(4xx 参数错、鉴权、内容策略、上下文超长)立即失败,不重试不降级
 * 按异常类名 + message 关键字启发式判断,非重试黑名单优先于瞬时白名单
 */
public final class TransientErrors {

    private TransientErrors() {}

    public static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String name = c.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String msg = c.getMessage() == null ? "" : c.getMessage().toLowerCase(Locale.ROOT);

            // 显式不可重试(优先,整条 cause 链一票否决):鉴权/内容策略/上下文超长/明确 4xx
            if (isNonRetryable(name, msg)) {
                return false;
            }
            // 瞬时白名单
            if (matchesTransient(name, msg)) {
                return true;
            }
        }
        return false;
    }

    /** 显式不可重试:鉴权 / 内容策略 / 上下文超长 / 明确 4xx 客户端错误 */
    private static boolean isNonRetryable(String name, String msg) {
        // 上下文超长 / token 超限:重试无用,需裁史或换模型窗口
        if (msg.contains("context length") || msg.contains("maximum context") || msg.contains("context_length")
                || msg.contains("context window") || msg.contains("too many tokens")
                || msg.contains("maximum tokens") || msg.contains("token budget")) {
            return true;
        }
        // 鉴权 / 授权
        if (msg.contains("invalid api key") || msg.contains("invalid_api_key") || msg.contains("api key")
                || msg.contains("unauthorized") || msg.contains("authentication")
                || msg.contains("forbidden") || msg.contains("permission denied")) {
            return true;
        }
        // 内容策略 / 安全过滤
        if (msg.contains("content filter") || msg.contains("content_filter") || msg.contains("content policy")
                || msg.contains("content_policy") || msg.contains("safety")) {
            return true;
        }
        // 明确 4xx 客户端错误(不含 429,429 属瞬时)
        if (msg.contains("400 ") || msg.contains(" 400") || msg.contains("bad request")
                || msg.contains("401 ") || msg.contains(" 401")
                || msg.contains("403 ") || msg.contains(" 403")
                || msg.contains("404 ") || msg.contains(" 404") || msg.contains("not found")
                || msg.contains("422 ") || msg.contains(" 422") || msg.contains("unprocessable")
                || msg.contains("invalid request") || msg.contains("invalid_request")) {
            return true;
        }
        return false;
    }

    /** 瞬时可重试:超时 / 网络 / 429 / 5xx / overloaded */
    private static boolean matchesTransient(String name, String msg) {
        if (name.contains("timeout") || name.contains("interruptedio")
                || name.contains("sockettimeout") || name.contains("connectexception")) {
            return true;
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return true;
        }
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")) {
            return true;
        }
        if (msg.contains(" 500") || msg.contains(" 502") || msg.contains(" 503") || msg.contains(" 504")
                || msg.contains("unavailable") || msg.contains("overloaded") || msg.contains("server error")) {
            return true;
        }
        if (msg.contains("connection reset") || msg.contains("connection refused") || msg.contains("stream reset")) {
            return true;
        }
        return false;
    }
}
