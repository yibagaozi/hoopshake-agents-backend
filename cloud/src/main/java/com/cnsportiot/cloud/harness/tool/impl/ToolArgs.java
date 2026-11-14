package com.cnsportiot.cloud.harness.tool.impl;

import java.util.Map;
import java.util.UUID;

/** 工具入参读取小工具:LLM/HTTP 传来的值可能是 String/Number,统一宽松解析 */
final class ToolArgs {

    private ToolArgs() {}

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

    /** 解析 UUID;缺省或非法返回 null(交由 port 兜底"最近一次") */
    static UUID getUuid(Map<String, Object> args, String key) {
        String s = getString(args, key);
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

