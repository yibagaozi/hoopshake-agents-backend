package com.cnsportiot.edge.exception;

import com.cnsportiot.contracts.error.ErrorCodeSpec;
import org.springframework.http.HttpStatus;

public enum EdgeErrorCode implements ErrorCodeSpec {

    // ── 409 冲突:边缘特有,与通用 STATE_CONFLICT 区分开以便前端分支
    /** CV 状态机不允许该操作;与会话状态冲突区分,操作台 CV 启停按钮据此禁用 */
    CV_STATE_CONFLICT(40911, HttpStatus.CONFLICT, "CV 当前状态不允许该操作"),
    /** 操作依赖课程上下文但尚未选课 */
    LESSON_NOT_SELECTED(40912, HttpStatus.CONFLICT, "尚未选课"),
    /** 尚未拉取参课名单 */
    ROSTER_NOT_LOADED(40913, HttpStatus.CONFLICT, "尚未拉取参课名单"),
    /** 学生在册但没有 ReID 特征,需现场注册 */
    GALLERY_MISSING(40914, HttpStatus.CONFLICT, "该学生尚未采集特征"),
    /** 出云要求算法交接文件已就绪,但 {session}/cloud/ingest.json 不存在,需先完成批处理 */
    HANDOFF_MISSING(40915, HttpStatus.CONFLICT, "算法交接文件不存在,请先完成课后批处理"),
    /** 已有人脸采集进程在跑;单机单采集机位,需等当前采集结束 */
    ENROLL_BUSY(40916, HttpStatus.CONFLICT, "已有人脸采集任务在进行中"),

    // ── 502:云端可达但拒绝了请求(与"不可达"区分,前者不该重试)
    CLOUD_REJECTED(50200, HttpStatus.BAD_GATEWAY, "云端拒绝了本次请求"),

    // ── 503:按下游细分。contracts 的 50300/50310 已占,edge 从 50320 起
    /** 云端不可达;可离线继续上课,数据回头补传 */
    CLOUD_UNREACHABLE(50320, HttpStatus.SERVICE_UNAVAILABLE, "云端不可达"),
    /** CV 进程不可用或启动失败 */
    CV_UNAVAILABLE(50330, HttpStatus.SERVICE_UNAVAILABLE, "CV 进程不可用"),
    /** 课后批处理编排未启用或命令未配置,无法对会话跑算法批处理 */
    BATCH_UNAVAILABLE(50331, HttpStatus.SERVICE_UNAVAILABLE, "课后批处理编排不可用"),
    /** 人脸采集编排未启用或命令未配置,无法拉起算法采集进程 */
    ENROLL_UNAVAILABLE(50332, HttpStatus.SERVICE_UNAVAILABLE, "人脸采集编排不可用"),
    /** ffmpeg 家族:进程本身不可用 */
    FFMPEG_UNAVAILABLE(50340, HttpStatus.SERVICE_UNAVAILABLE, "ffmpeg 不可用或录制进程启动失败"),
    /** ffmpeg 家族:采集进程在跑但该路帧号停滞 */
    CAMERA_OFFLINE(50341, HttpStatus.SERVICE_UNAVAILABLE, "机位离线"),
    /** 本地流媒体服务未就绪,ffmpeg 无处推流 */
    MEDIAMTX_NOT_READY(50350, HttpStatus.SERVICE_UNAVAILABLE, "本地流媒体服务未就绪"),

    // ── 507 存储
    DISK_SPACE_LOW(50700, HttpStatus.INSUFFICIENT_STORAGE, "本地存储不足"),
    /** 对象存储(MinIO)上传失败 */
    STORAGE_FAILED(50701, HttpStatus.INSUFFICIENT_STORAGE, "对象存储上传失败");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    EdgeErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public String error() {
        return name();
    }
}

