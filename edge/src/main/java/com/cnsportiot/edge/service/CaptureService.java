package com.cnsportiot.edge.service;

import com.cnsportiot.edge.dto.ConsoleDtos.StateResponse;

/** 采集控制与状态面板 */
public interface CaptureService {

    /** 启动 mediamtx 与四路采集;应用启动时自动调用一次 */
    void startAll();

    /** 重启单路采集;camId 为 null 则全部重启 */
    void restart(String camId);

    /** 操作台首屏全量快照 */
    StateResponse state();
}
