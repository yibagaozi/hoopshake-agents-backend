package com.cnsportiot.cloud.ops.tool;

import java.util.Map;

/**
 * 运维工具入参读取小工具(与 harness 的 {@code ToolArgs} 同源,因后者包私有故在运维包内自备一份,
 * 保持"运维包自包含")。LLM/HTTP 传来的值可能是 String/Number,统一宽松解析
 */
final class OpsToolArgs {

    private OpsToolArgs() {}

    static int getInt(Map<String, Object> args, String key, int def) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null && !String.valueOf(v).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignore) {
                // 落回默认
            }
        }
        return def;
    }

    static String getString(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v).trim();
    }
}
