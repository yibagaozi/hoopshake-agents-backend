package com.cnsportiot.edge.domain;

import com.cnsportiot.edge.domain.enums.ProcessState;

/** 机位运行时状态,由采集侧读数聚合而成 */
public class CameraStatus {

    private final String camId;

    /** 以下三项仅由该机位的 FfmpegProgressReader 单线程写入,故用 volatile 而非 Atomic */
    private volatile long lastFrameNo = -1;
    private volatile long lastFrameAtMillis;
    private volatile double fps;

    /** blackdetect 判定:true = 当前处于黑屏/无信号 */
    private volatile boolean black;

    private volatile ProcessState captureState = ProcessState.STOPPED;

    /** MediaMTX 侧该路是否有发布者(取自 mediamtx API) */
    private volatile boolean published;

    public CameraStatus(String camId) {
        this.camId = camId;
    }

    /** 帧号变化即刷新活性时间戳 */
    public void onProgress(long frameNo, double fps) {
        this.fps = fps;
        if (frameNo != lastFrameNo) {
            lastFrameNo = frameNo;
            lastFrameAtMillis = System.currentTimeMillis();
        }
    }

    public void onBlackChanged(boolean black) {
        this.black = black;
    }

    /** 在线判定:帧号在 staleMillis 内推进过 */
    public boolean online(long staleMillis) {
        return lastFrameAtMillis > 0
                && System.currentTimeMillis() - lastFrameAtMillis < staleMillis;
    }

    /** 有信号 = 在线且非黑屏 */
    public boolean signal(long staleMillis) {
        return online(staleMillis) && !black;
    }

    public String camId() {
        return camId;
    }

    public double fps() {
        return fps;
    }

    public boolean black() {
        return black;
    }

    public ProcessState captureState() {
        return captureState;
    }

    public void captureState(ProcessState state) {
        this.captureState = state;
    }

    public boolean published() {
        return published;
    }

    public void published(boolean published) {
        this.published = published;
    }
}
