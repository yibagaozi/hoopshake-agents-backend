package com.cnsportiot.cloud.harness.tool;

/**
 * 工具调用过程事件回调,供 {@link ToolRunner} 在运行/成功/拒绝/失败各阶段回调
 * 对话链路里由网关桥接到 SSE 的 {@code tool} 事件;调试端点用 {@link #NOOP}
 */
@FunctionalInterface
public interface ToolEventListener {

    // 状态取值(与 SSE tool 事件 status 对齐)
    String RUNNING = "running";
    String OK = "ok";
    String DENIED = "denied";
    String ERROR = "error";

    void onEvent(String toolName, String status, String label);

    ToolEventListener NOOP = (name, status, label) -> { };
}
