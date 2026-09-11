package com.cnsportiot.cloud.ops.service.impl;

import com.cnsportiot.cloud.ops.dto.EdgeHeartbeatRequest;
import com.cnsportiot.cloud.ops.dto.OpsDtos.EdgeHeartbeatAck;
import com.cnsportiot.cloud.ops.entity.EdgeDevice;
import com.cnsportiot.cloud.ops.evaluator.EdgeHealthEvaluator;
import com.cnsportiot.cloud.ops.repository.EdgeDeviceRepository;
import com.cnsportiot.cloud.ops.service.EdgeHeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/** 心跳 upsert:存在则覆盖当前态并刷新 last_seen_at,否则新建 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EdgeHeartbeatServiceImpl implements EdgeHeartbeatService {

    private final EdgeDeviceRepository repo;
    private final EdgeHealthEvaluator healthEvaluator;

    @Override
    @Transactional
    public EdgeHeartbeatAck heartbeat(EdgeHeartbeatRequest req, String sourceIp) {
        OffsetDateTime seenAt = req.occurredAt() != null ? req.occurredAt() : OffsetDateTime.now();
        EdgeDevice device = repo.findByDeviceId(req.deviceId()).orElseGet(() ->
                EdgeDevice.builder().deviceId(req.deviceId()).lastSeenAt(seenAt).build());

        if (req.name() != null) device.setName(req.name());
        if (req.courtId() != null) device.setCourtId(req.courtId());
        if (req.reportedStatus() != null) device.setReportedStatus(req.reportedStatus());
        if (req.appVersion() != null) device.setAppVersion(req.appVersion());
        if (req.firmware() != null) device.setFirmware(req.firmware());
        if (req.metrics() != null) device.setMetrics(req.metrics());
        device.setLastError(req.lastError());   // null 即清除上次错误
        if (sourceIp != null) device.setIpAddress(sourceIp);
        device.setLastSeenAt(seenAt);

        EdgeDevice saved = repo.save(device);
        return new EdgeHeartbeatAck(saved.getDeviceId(),
                healthEvaluator.evaluate(saved.getLastSeenAt()), saved.getLastSeenAt());
    }
}
