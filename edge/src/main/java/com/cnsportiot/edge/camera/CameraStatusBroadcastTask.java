package com.cnsportiot.edge.camera;

import com.cnsportiot.edge.domain.CameraDescriptor;
import com.cnsportiot.edge.domain.CameraStatus;
import com.cnsportiot.edge.realtime.EdgeEventPublisher;
import com.cnsportiot.edge.realtime.WsEventType;
import com.cnsportiot.edge.realtime.WsEvents;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** 机位状态广播:2s 一轮,仅在 online/signal 变化时推送 */
@Component
public class CameraStatusBroadcastTask {

    private final CameraRegistry registry;
    private final EdgeEventPublisher publisher;

    /** camId → 上次推送的 "online|signal",用于变化检测 */
    private final Map<String, String> lastSent = new HashMap<>();

    public CameraStatusBroadcastTask(CameraRegistry registry, EdgeEventPublisher publisher) {
        this.registry = registry;
        this.publisher = publisher;
    }

    @Scheduled(fixedRate = 2000)
    public void broadcast() {
        long stale = registry.staleFrameMillis();
        for (CameraDescriptor cam : registry.all()) {
            CameraStatus st = registry.status(cam.camId());
            boolean online = st.online(stale);
            boolean signal = st.signal(stale);

            String key = online + "|" + signal;
            if (key.equals(lastSent.get(cam.camId()))) {
                continue;
            }
            lastSent.put(cam.camId(), key);
            publisher.publish(WsEventType.CAMERA_STATUS, new WsEvents.CameraStatusEvent(
                    cam.camId(), cam.role(), online, signal, st.fps()));
        }
    }
}
