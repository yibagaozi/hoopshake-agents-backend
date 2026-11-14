package com.cnsportiot.edge.capture;

import com.cnsportiot.edge.domain.CameraStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 解析 ffmpeg stderr 中的 blackdetect 输出,判定"连着但黑屏/无信号" */
public class BlackdetectReader implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BlackdetectReader.class);

    private final InputStream stderr;
    private final CameraStatus status;

    public BlackdetectReader(InputStream stderr, CameraStatus status) {
        this.stderr = stderr;
        this.status = status;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("blackdetect")) {
                    // 带 black_end 说明黑屏区间已闭合,否则是刚进入黑屏
                    status.onBlackChanged(!line.contains("black_end"));
                } else if (line.contains("error") || line.contains("Error")) {
                    log.warn("[{}] ffmpeg: {}", status.camId(), line);
                } else {
                    log.trace("[{}] ffmpeg: {}", status.camId(), line);
                }
            }
        } catch (Exception e) {
            log.debug("stderr 流结束: {}", status.camId(), e);
        }
    }
}

