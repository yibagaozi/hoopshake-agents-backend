package com.cnsportiot.edge.domain.enums;

/** 受托管外部进程(mediamtx / ffmpeg / CV)的状态机 */
public enum ProcessState {

    STOPPED,
    STARTING,
    RUNNING,

    /** 运行中但指标异常(如帧号停滞) */
    DEGRADED,

    STOPPING,

    /** 连续重启失败达上限,不再自动拉起 */
    FAILED
}
