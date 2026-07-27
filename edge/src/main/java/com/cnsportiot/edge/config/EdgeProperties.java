package com.cnsportiot.edge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 边缘侧配置 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hoopshake.edge")
public class EdgeProperties {

    private String edgeId = "edge-01";

    /** 数据根目录,录制文件落在 {data-root}/sessions/{sessionId}/ 下 */
    private String dataRoot;

    private String ffmpegPath = "ffmpeg";

    /** 帧号超过该毫秒数未推进即判定机位离线 */
    private long staleFrameMillis = 3000;

    private List<Camera> cameras = new ArrayList<>();

    private MediaMtx mediamtx = new MediaMtx();

    private Encode encode = new Encode();

    private Cloud cloud = new Cloud();

    @Getter
    @Setter
    public static class Camera {
        private String camId;
        private String deviceName;
        private String role;
        private boolean anchor;
    }

    @Getter
    @Setter
    public static class MediaMtx {
        private String executable;
        private String config;
        private String rtspBase = "rtsp://127.0.0.1:8554";
        private String apiBase = "http://127.0.0.1:9997";
        /** 启动后等待端口就绪的上限 */
        private long readyTimeoutMillis = 10_000;
    }

    /** 采集侧编码参数 */
    @Getter
    @Setter
    public static class Encode {
        private String videoSize = "1920x1080";
        private int framerate = 60;
        private String rtbufsize = "512M";
        private String codec = "h264_nvenc";
        private String preset = "p4";
        private int cq = 23;
        private String bitrate = "8M";
        private String maxrate = "12M";
        private int gop = 60;
        private String pixFmt = "yuv420p";
    }

    @Getter
    @Setter
    public static class Cloud {
        private String baseUrl;
        private String serviceToken;
        private long connectTimeoutMillis = 3000;
        private long readTimeoutMillis = 10_000;
    }
}
