package com.cnsportiot.cloud.harness.llm;

/** 可注入的等待抽象 */
@FunctionalInterface
public interface Sleeper {

    void sleepMillis(long millis);

    /** 真实等待;被中断则复位中断标志并上抛,让上层尽快结束 */
    Sleeper REAL = millis -> {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("退避等待被中断", e);
        }
    };
}
