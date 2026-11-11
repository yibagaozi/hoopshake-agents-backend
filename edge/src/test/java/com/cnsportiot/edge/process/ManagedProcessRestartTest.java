package com.cnsportiot.edge.process;

import com.cnsportiot.edge.domain.enums.ProcessState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 一启动就退的持续性故障:设了稳定运行时长后,累计到 maxFailures 次即停止重启 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ManagedProcessRestartTest {

    @Test
    void shortLivedFailures_accumulate_whenStableUptimeSet() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ManagedProcess p = new ManagedProcess(
                new ProcessSpec("t", List.of("sh", "-c", "exit 3"), null, null, null, null, true, 3),
                new ManagedProcessListener() {
                    @Override public void onStarted(String name, long pid) { starts.incrementAndGet(); }
                    @Override public void onFailed(String name, Long pid, String reason) { failed.incrementAndGet(); }
                })
                .stableUptime(Duration.ofSeconds(60));

        Thread t = new Thread(p);
        t.start();
        t.join(15_000);

        assertThat(t.isAlive()).isFalse();
        assertThat(starts.get()).isEqualTo(3);
        assertThat(failed.get()).isEqualTo(1);
        assertThat(p.state()).isEqualTo(ProcessState.STOPPED);
    }
}
