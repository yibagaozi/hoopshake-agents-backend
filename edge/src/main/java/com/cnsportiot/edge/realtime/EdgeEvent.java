package com.cnsportiot.edge.realtime;

/** 进程内事件。生产方只管发布 */
public record EdgeEvent(WsEventType type, Object payload) {
}
