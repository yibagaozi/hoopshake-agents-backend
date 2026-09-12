package com.cnsportiot.edge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
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

    private Batch batch = new Batch();

    private Live live = new Live();

    private Enroll enroll = new Enroll();

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

    /**
     * 课后算法批处理编排:下课(或手动触发)→ 对该 session 的 raw/ 跑算法批处理 →
     * 算法写 {@code cloud/ingest.json} 交接文件 → 编排器发 {@code sessionProcessed} → 出云。
     * 默认关闭:未接入算法入口时保持原行为(下课只停录制,出云靠手动 /publish 或 CV 上行)。
     */
    @Getter
    @Setter
    public static class Batch {
        /** 编排总开关。关时 stop() 不自动跑批处理,{@code POST /local/session/{id}/process} 报 BATCH_UNAVAILABLE。 */
        private boolean enabled = false;
        /** 下课(stop)是否自动触发;false 时只能经 {@code POST /local/session/{id}/process} 手动触发。 */
        private boolean autoOnStop = true;
        /** 批处理命令行模板,首元素为可执行文件;支持占位符 {sessionId} {dataDir} {rawDir} {cloudDir}。 */
        private List<String> command = new ArrayList<>();
        private String workDir;
        /** 注入子进程的环境变量;python 建议 PYTHONUNBUFFERED=1。 */
        private Map<String, String> env = new LinkedHashMap<>();
        /** 单次批处理墙钟上限,超时强杀且不出云。 */
        private Duration timeout = Duration.ofMinutes(30);
    }

    /**
     * 算法 v2.2.0 直播对接。WS 沿用原设计:<b>算法作客户端连 edge 的 {@code /internal/cv/stream} 服务端并推事件</b>
     * (edge 不反向连算法),故此处无 WS 地址;edge 收 action_finalized/timeline_gap 后经 LiveActionListener 处理。
     * 身份绑定表(人脸→学号→UUID)缓存本地,不上云。
     */
    @Getter
    @Setter
    public static class Live {
        /** 算法直播产出根目录(读 enrollment.json + 注册缩略图),= 算法仓库 data/outputs/live。 */
        private String algoOutputsDir = "C:/hoopshake/algo/data/outputs/live";
        /** 身份绑定表缓存文件(相对 data-root)。 */
        private String bindingRelPath = "identity/bindings.json";
        /** 缺身份绑定时:是否仍把动作以“未归属”落库(false=只上屏不落库)。 */
        private boolean persistUnbound = false;
    }

    /**
     * 现场人脸采集编排:前端点“开始采集” → edge 拉起算法 {@code run_live_ws.py enroll --session={课程id}} →
     * 算法把注册结果写 {@code {live.algoOutputsDir}/{session}/enrollment.json} + 缩略图 →
     * 前端轮询 {@code /local/enroll/status} 转 SUCCEEDED 后拉 {@code /local/enroll/identities?session={课程id}} 绑学号。
     * <p>约定 {@code session = 课程id(lessonId)},故前端 start / status / identities 三处用同一个值。
     * 默认关闭:未接算法入口时保持原行为(采脸靠人工在算法机上跑)。
     */
    @Getter
    @Setter
    public static class Enroll {
        /** 编排总开关。关时 {@code POST /local/enroll/start} 报 ENROLL_UNAVAILABLE。 */
        private boolean enabled = false;
        /** python 可执行文件(或虚拟环境内的 python)。 */
        private String pythonExecutable = "python";
        /** {@code run_live_ws.py} 相对 workDir 的路径。 */
        private String scriptPath = "scripts/run_live_ws.py";
        /** 算法仓库根目录({@code PYTHONPATH=.} 相对此目录);null 用进程当前目录。 */
        private String workDir;
        /** 默认采集机位(可被请求覆盖)。 */
        private String enrollCamera = "cam_03";
        /** 默认采集时长秒(可被请求覆盖)。 */
        private double seconds = 45.0;
        /** 默认采样帧率(可被请求覆盖)。 */
        private int sampleHz = 8;
        /** 追加到命令尾部的固定参数(相机/RTSP/anchor 等),如 {@code --rtsp-json xxx} 或 {@code --cam_03 rtsp://...}。 */
        private List<String> extraArgs = new ArrayList<>();
        /** 注入子进程的环境变量;python 建议 {@code PYTHONUNBUFFERED=1} 以实时看日志。 */
        private Map<String, String> env = new LinkedHashMap<>();
        /** 单次采集墙钟上限,超时强杀并标记 FAILED。 */
        private Duration timeout = Duration.ofMinutes(5);
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
