package com.cnsportiot.cloud.ops.service;

import com.cnsportiot.cloud.ops.dto.EdgeHeartbeatRequest;
import com.cnsportiot.cloud.ops.dto.OpsDtos.EdgeHeartbeatAck;

/** 边缘设备心跳:按 deviceId 幂等 upsert 当前态。 */
public interface EdgeHeartbeatService {

    EdgeHeartbeatAck heartbeat(EdgeHeartbeatRequest request, String sourceIp);
}
