package com.cnsportiot.edge.capture;

import com.cnsportiot.edge.domain.CameraStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 解析 ffmpeg {@code -progress pipe:1} 的 key=value 流,提取 frame / fps */
public class FfmpegProgressReader implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(FfmpegProgressReader.class);

    private final InputStream stdout;
    private final CameraStatus status;

    public FfmpegProgressReader(InputStream stdout, CameraStatus status) {
        this.stdout = stdout;
        this.status = status;
    }

    @Override
    public void run() {
        long frame = -1;
        double fps = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stdout, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "frame" -> frame = parseLong(frame, value);
                    case "fps" -> fps = parseDouble(fps, value);
                    // 一个进度块结束,提交读数
                    case "progress" -> {
                        if (frame >= 0) {
                            status.onProgress(frame, fps);
                        }
                    }
                    default -> { }
                }
            }
        } catch (Exception e) {
            log.debug("progress 流结束: {}", status.camId(), e);
        }
    }

    private static long parseLong(long fallback, String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(double fallback, String v) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

