package com.cnsportiot.edge.service;

import com.cnsportiot.edge.dto.RecordDtos.RecordStatusResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordStopResponse;
import com.cnsportiot.edge.dto.RecordDtos.TodayRecordingsResponse;
import com.cnsportiot.edge.dto.RecordDtos.RecordStartResponse;

/** 录制管理 */
public interface RecordService {

    /** 开始录制 */
    RecordStartResponse start();

    /** 结束录制 */
    RecordStopResponse stop();

    /** 当前录制状态,轮询兜底 */
    RecordStatusResponse status();

    /** 今日录制记录;dir 可空,缺省取 data-root */
    TodayRecordingsResponse today(String dir);
}

