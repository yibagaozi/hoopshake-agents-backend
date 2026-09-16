package com.cnsportiot.edge.process;

public interface ManagedProcessListener {

    default void onStarted(String name, long pid) {}

    default void onExited(String name, long pid, int exitCode, boolean expected) {}

    default void onRestarting(String name, long pid, int exitCode, long delayMillis) {}

    default void onFailed(String name, Long pid, String reason) {}
}
