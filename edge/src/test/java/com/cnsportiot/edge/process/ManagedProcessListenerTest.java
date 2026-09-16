package com.cnsportiot.edge.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedProcessListenerTest {

    @Test
    void run_reportsStartExitAndFailureWhenProcessGivesUp() {
        RecordingListener listener = new RecordingListener();
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ManagedProcess process = new ManagedProcess(
                new ProcessSpec("test-java", List.of(javaBin, "-version"),
                        null, null, null, null, false, 1),
                listener);

        process.run();

        assertThat(listener.started).isTrue();
        assertThat(listener.pid).isNotNull();
        assertThat(listener.exitCode.get()).isZero();
        assertThat(listener.expectedExit.get()).isFalse();
        assertThat(listener.failureReason.get()).isEqualTo("max consecutive failures reached");
    }

    private static final class RecordingListener implements ManagedProcessListener {
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicReference<Long> pid = new AtomicReference<>();
        private final AtomicInteger exitCode = new AtomicInteger();
        private final AtomicBoolean expectedExit = new AtomicBoolean();
        private final AtomicReference<String> failureReason = new AtomicReference<>();

        @Override
        public void onStarted(String name, long pid) {
            this.started.set(true);
            this.pid.set(pid);
        }

        @Override
        public void onExited(String name, long pid, int exitCode, boolean expected) {
            this.exitCode.set(exitCode);
            this.expectedExit.set(expected);
        }

        @Override
        public void onFailed(String name, Long pid, String reason) {
            this.failureReason.set(reason);
        }
    }
}
