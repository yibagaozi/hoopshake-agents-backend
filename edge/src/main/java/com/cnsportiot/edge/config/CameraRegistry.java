package com.cnsportiot.edge.config;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.domain.CameraDescriptor;
import com.cnsportiot.edge.domain.CameraStatus;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 机位配置与运行时状态表 */
@Component
public class CameraRegistry {

    private final EdgeProperties props;
    private final Map<String, CameraDescriptor> descriptors = new LinkedHashMap<>();
    private final Map<String, CameraStatus> statuses = new LinkedHashMap<>();

    public CameraRegistry(EdgeProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        for (EdgeProperties.Camera c : props.getCameras()) {
            CameraDescriptor d = new CameraDescriptor(
                    c.getCamId(), c.getDeviceName(), c.getRole(), c.isAnchor());
            descriptors.put(d.camId(), d);
            statuses.put(d.camId(), new CameraStatus(d.camId()));
        }
    }

    public List<CameraDescriptor> all() {
        return List.copyOf(descriptors.values());
    }

    public CameraDescriptor descriptor(String camId) {
        CameraDescriptor d = descriptors.get(camId);
        if (d == null) {
            throw new BusinessException(EdgeErrorCode.CAMERA_NOT_FOUND, "机位不存在: " + camId);
        }
        return d;
    }

    public CameraStatus status(String camId) {
        CameraStatus s = statuses.get(camId);
        if (s == null) {
            throw new BusinessException(EdgeErrorCode.CAMERA_NOT_FOUND, "机位不存在: " + camId);
        }
        return s;
    }

    public List<CameraStatus> allStatuses() {
        return List.copyOf(statuses.values());
    }

    /**
     * 帧号仍在推进的机位。判定用 online 而非 signal:
     * 黑屏(signal=false)时流依旧存在、可正常录制,只是画面全黑;
     * 离线(online=false)则根本没有流,录制进程起来也只会立刻退出
     */
    public List<String> onlineCamIds() {
        long stale = props.getStaleFrameMillis();
        return statuses.values().stream()
                .filter(s -> s.online(stale))
                .map(CameraStatus::camId)
                .toList();
    }

    /** 帧号停滞的机位,录制时需跳过并提示操作台 */
    public List<String> offlineCamIds() {
        long stale = props.getStaleFrameMillis();
        return statuses.values().stream()
                .filter(s -> !s.online(stale))
                .map(CameraStatus::camId)
                .toList();
    }

    /** 主锚点机位,现场注册取流用;未配置则回退到第一路 */
    public CameraDescriptor anchor() {
        return descriptors.values().stream()
                .filter(CameraDescriptor::anchor)
                .findFirst()
                .orElseGet(() -> descriptors.values().iterator().next());
    }

    public long staleFrameMillis() {
        return props.getStaleFrameMillis();
    }
}

