package com.cnsportiot.edge.service;

import com.cnsportiot.edge.dto.SessionDtos.RecordingHealth;
import com.cnsportiot.edge.dto.SessionDtos.SessionResponse;
import com.cnsportiot.edge.dto.SessionDtos.StartSessionRequest;
import com.cnsportiot.edge.dto.SessionDtos.StopSessionResponse;

/** 上课与录制生命周期 */
public interface SessionService {

    /** 开始上课:建 sessionId、建目录、拉起四路录制 */
    SessionResponse start(StartSessionRequest request);

    /** 暂停:停当前录制片段,课未结束 */
    SessionResponse pause();

    /** 继续:以新时间戳开新一段录制 */
    SessionResponse resume();

    /** 下课:停录制、写 meta.json、落 spool 待上报 */
    StopSessionResponse stop();

    /** 当前会话状态;未开录时反映选课与否(IDLE / READY) */
    SessionResponse current();

    /** 各路录制进程存活情况 */
    RecordingHealth health();
}
