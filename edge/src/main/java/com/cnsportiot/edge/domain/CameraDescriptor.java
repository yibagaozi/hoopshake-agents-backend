package com.cnsportiot.edge.domain;

/**
 * 机位静态配置,来自 EdgeProperties
 *
 * @param camId      机位编号,如 cam_01;同时作为 RTSP 路径名
 * @param deviceName dshow/v4l2 设备名
 * @param role       机位角色描述,仅用于 UI 展示
 * @param anchor     是否主锚点机位(现场注册取流用)
 */
public record CameraDescriptor(
        String camId,
        String deviceName,
        String role,
        boolean anchor) {
}
