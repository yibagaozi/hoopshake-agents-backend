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

    private Minio minio = new Minio();

    private Publish publish = new Publish();

    private Ingest ingest = new Ingest();

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

    /**
     * 对象存储(MinIO / S3 兼容)。逐帧 motion.jsonl 与 gallery 特征上传到此,
     * 回填 action_clip.motion_uri / reid_gallery.storage_uri。默认关闭:未配置时用 NoopObjectStore。
     */
    @Getter
    @Setter
    public static class Minio {
        private boolean enabled = false;
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey;
        private String secretKey;
        private String bucket = "hoopshake";
        /**
         * 回填 URI 的方案:{@code s3}(默认,返回 {@code s3://bucket/key},不可点开但稳定不透明)
         * 或 {@code url}(返回 {@code {public-base-url}/bucket/key},可直接拉取)。
         */
        private String uriScheme = "s3";
        /** uriScheme=url 时用于拼可访问地址;缺省用 endpoint。 */
        private String publicBaseUrl;
    }

    /** 会话结束出云:读算法交接文件 → 上传 motion → 推 action-clips / session / gallery。 */
    @Getter
    @Setter
    public static class Publish {
        private boolean enabled = true;
        /** 算法在 session 目录下写的交接文件相对路径(cloud 就绪载荷)。 */
        private String handoffRelPath = "cloud/ingest.json";
    }

    /** 出云选路。批处理(session/clips/gallery)可走 MQ;实时反馈始终走 HTTP。 */
    @Getter
    @Setter
    public static class Ingest {
        private Mq mq = new Mq();

        /** 批处理出云 MQ(RabbitMQ)。关闭时 SessionCloudPublisher 走 HTTP(默认)。 */
        @Getter
        @Setter
        public static class Mq {
            private boolean enabled = false;
            /** 与云端 hoopshake.ingest 一致的 topic 交换机名。 */
            private String exchange = "hoopshake.ingest";
        }
    }

}
