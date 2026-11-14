package com.cnsportiot.edge.domain.enums;

public enum RecordState {

    IDLE,
    RECORDING,
    STOPPING;

    /** 由会话状态推导 */
    public static RecordState from(SessionState sessionState) {
        return sessionState == SessionState.RECORDING ? RECORDING : IDLE;
    }
}
