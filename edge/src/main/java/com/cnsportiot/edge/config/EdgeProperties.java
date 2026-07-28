package com.cnsportiot.edge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private Cv cv = new Cv();

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
        /** 注入 mediamtx 进程的环境变量,键名为 MTX_ + 配置项名(全大写) */
        private Map<String, String> env = new LinkedHashMap<>();
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

    /** CV 进程托管 */
    @Getter
    @Setter
    public static class Cv {
        private boolean enabled = false;
        /** 应用就绪后自动拉起;false 则只能经 /local/cv/start 手动启动 */
        private boolean autoStart = true;
        /** 完整命令行,首元素为可执行文件 */
        private List<String> command = new ArrayList<>();
        private String workDir;
        /** 注入子进程的环境变量;python 需 PYTHONUNBUFFERED=1 才能实时看到日志 */
        private Map<String, String> env = new LinkedHashMap<>();
        private boolean autoRestart = true;
        private int maxFailures = 5;
    }

}
