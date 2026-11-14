package com.cnsportiot.edge.realtime;

import java.time.OffsetDateTime;

/**
 * WS 帧统一结构,每帧一条 JSON
 *
 * <pre>
 * { "type": "cue", "seq": 1042, "ts": "2026-07-26T15:30:12.480+08:00", "payload": { … } }
 * </pre>
 *
 * @param seq 单调递增,连接内唯一;前端据此发现丢帧(骨架帧允许丢)
 */
public record WsEnvelope(
        String type,
        long seq,
        OffsetDateTime ts,
        Object payload) {

    public static WsEnvelope of(WsEventType type, long seq, Object payload) {
        return new WsEnvelope(type.wireName(), seq, OffsetDateTime.now(), payload);
    }
}
