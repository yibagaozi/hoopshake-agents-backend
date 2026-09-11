package com.cnsportiot.cloud.ops.repository;

import com.cnsportiot.cloud.ops.entity.EdgeDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 场边设备仓储 */
public interface EdgeDeviceRepository extends JpaRepository<EdgeDevice, UUID> {

    Optional<EdgeDevice> findByDeviceId(String deviceId);

    List<EdgeDevice> findAllByOrderByLastSeenAtDesc();

    /** 在线设备数(last_seen_at 晚于阈值时刻) */
    long countByLastSeenAtAfter(OffsetDateTime threshold);
}
