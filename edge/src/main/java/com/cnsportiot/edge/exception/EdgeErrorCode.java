package com.cnsportiot.edge.exception;

import com.cnsportiot.contracts.error.ErrorCodeSpec;
import org.springframework.http.HttpStatus;

public enum EdgeErrorCode implements ErrorCodeSpec {

    // 6000x 采集与推流
    CAMERA_NOT_FOUND(60000, HttpStatus.NOT_FOUND, "机位不存在"),
    CAMERA_OFFLINE(60001, HttpStatus.SERVICE_UNAVAILABLE, "机位离线"),
    CAPTURE_NOT_RUNNING(60002, HttpStatus.CONFLICT, "采集未启动"),
    MEDIAMTX_NOT_READY(60003, HttpStatus.SERVICE_UNAVAILABLE, "本地流媒体服务未就绪"),

    // 6001x Session 与录制
    SESSION_ALREADY_RUNNING(60010, HttpStatus.CONFLICT, "已有进行中的录制"),
    SESSION_NOT_RUNNING(60011, HttpStatus.CONFLICT, "当前没有进行中的录制"),
    SESSION_NOT_FOUND(60012, HttpStatus.NOT_FOUND, "录制会话不存在"),
    RECORDING_START_FAILED(60013, HttpStatus.INTERNAL_SERVER_ERROR, "录制启动失败"),
    SESSION_STATE_INVALID(60014, HttpStatus.CONFLICT, "当前会话状态不允许该操作"),
    LESSON_NOT_SELECTED(60015, HttpStatus.CONFLICT, "尚未选课"),

    // 6002x 本地资源
    DISK_SPACE_LOW(60020, HttpStatus.INSUFFICIENT_STORAGE, "本地存储不足"),
    DATA_DIR_UNWRITABLE(60021, HttpStatus.INTERNAL_SERVER_ERROR, "数据目录不可写"),

    // 6003x 名单与注册
    ROSTER_NOT_LOADED(60030, HttpStatus.CONFLICT, "尚未拉取参课名单"),
    STUDENT_NOT_IN_ROSTER(60031, HttpStatus.NOT_FOUND, "该学号不在本课名单中"),
    GALLERY_MISSING(60032, HttpStatus.CONFLICT, "该学生尚未采集特征"),

    // 6004x 云端联通
    CLOUD_UNREACHABLE(60040, HttpStatus.SERVICE_UNAVAILABLE, "云端不可达"),
    CLOUD_REJECTED(60041, HttpStatus.BAD_GATEWAY, "云端拒绝了本次请求");

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

