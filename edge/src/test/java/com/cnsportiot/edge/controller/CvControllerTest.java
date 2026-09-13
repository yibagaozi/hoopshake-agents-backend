package com.cnsportiot.edge.controller;

import com.cnsportiot.edge.cv.CvProcessManager;
import com.cnsportiot.edge.cv.CvProcessManager.CvStatus;
import com.cnsportiot.edge.domain.enums.ProcessState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CvController:启动/停止/重启/状态四个按钮端点都委托给 CvProcessManager;start 透传 session。 */
class CvControllerTest {

    private CvProcessManager cv;
    private CvController controller;

    @BeforeEach
    void setup() {
        cv = mock(CvProcessManager.class);
        when(cv.status()).thenReturn(new CvStatus(ProcessState.RUNNING, "L1"));
        controller = new CvController(cv);
    }

    @Test void start_withSession_delegates() {
        controller.start("L1");
        verify(cv).start("L1");
    }

    @Test void start_noSession_delegatesNull() {
        controller.start(null);
        verify(cv).start((String) null);
    }

    @Test void stop_delegates() {
        controller.stop();
        verify(cv).stop();
    }

    @Test void restart_delegates() {
        controller.restart();
        verify(cv).restart();
    }

    @Test void status_returnsManagerStatus() {
        assertThat(controller.status().data().state()).isEqualTo(ProcessState.RUNNING);
        assertThat(controller.status().data().session()).isEqualTo("L1");
    }
}
