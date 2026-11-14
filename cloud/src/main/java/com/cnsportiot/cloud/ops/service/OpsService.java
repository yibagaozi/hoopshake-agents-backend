package com.cnsportiot.cloud.ops.service;

import com.cnsportiot.cloud.ops.dto.OpsDtos.*;

/** 运维只读聚合(见 docs/ops/ops-observability-design.md §4)。也是未来运维 Agent 工具的共享取数端口。 */
public interface OpsService {

    /** 首屏一次拉全:业务量 + Agent 表现 + 系统健康 + 边缘汇总。 */
    OverviewResponse overview(int agentWindowHours);

    /** 业务量:课程 / 数据 / 学生。 */
    BusinessResponse business();

    /** Agent 表现近窗聚合(小时)。 */
    AgentQualityResponse agentQuality(int windowHours);

    /** 系统健康(韧性快照:熔断 / 舱壁 / 限流)。 */
    SystemHealthResponse systemHealth();

    /** 边缘设备列表(可按派生健康态过滤;null = 全部)。 */
    EdgeDeviceListResponse edgeDevices(EdgeHealth filter);

    /** 单设备详情;不存在返回 null(控制器转 404)。 */
    EdgeDeviceResponse edgeDevice(String deviceId);
}
