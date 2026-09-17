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

    private Calibration calibration = new Calibration();

    private Telemetry telemetry = new Telemetry();

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
        /**
         * 是否在应用就绪后自动拉起。<b>默认 false</b>:CV 改由“开始上课”按 session 拉起、或操作台
         * “启动算法”按钮手动拉起,不再随 edge 启动就跑(否则会用错的 session、且空转占 GPU)。
         */
        private boolean autoStart = false;
        /**
         * 完整命令行,首元素为可执行文件。可含占位符 {@code {session}},启动时替换成实际 session
         * (= 课程 id / 手动指定 / default-session),让算法 {@code run --session/--gallery-session} 对上注册库。
         */
        private List<String> command = new ArrayList<>();
        private String workDir;
        /** 注入子进程的环境变量;python 需 PYTHONUNBUFFERED=1 才能实时看到日志 */
        private Map<String, String> env = new LinkedHashMap<>();
        private boolean autoRestart = true;
        private int maxFailures = 5;
        /** 未指定 session 时(手动按钮不带参数 / auto-start)用的默认 session。 */
        private String defaultSession = "live";
    }

    /**
     * 对象存储(MinIO / S3 兼容)。逐帧 motion.jsonl 与 gallery 特征上传到此,
     * 回填 action_clip.motion_uri / reid_gallery.storage_uri。默认关闭:未配置时用 NoopObjectStore
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

    /** 会话结束出云:读算法交接文件 → 上传 motion → 推 action-clips / session / gallery */
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
     * 默认关闭:未接入算法入口时保持原行为(下课只停录制,出云靠手动 /publish 或 CV 上行)
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

    /**
     * 球场标定编排(直播课口径,见《球场控制点标定》§直播课 v2.2.0)。
     *
     * <p>产物目录按课程隔离:{@code {workDir}/{output-root}/{dir-prefix}{课程id}/},
     * 即 {@code data/calibration/live_{lessonId}/} —— <b>绝不覆盖离线的 v2_4cam_zoned</b>。
     * 上课拉起 CV 前先查这个目录里的必备文件,缺就按配置自动跑一次标定。
     *
     * <pre>
     * hoopshake:
     *   edge:
     *     calibration:
     *       enabled: true
     *       python-executable: C:/.../python.exe
     *       script-path: scripts/run_live_ws.py
     *       work-dir: C:/hoopshake/algo
     *       output-root: data/calibration
     *       dir-prefix: live_
     *       required-files: [cameras.json, camera_centers_world.json]
     *       required-cameras: [cam_01, cam_02, cam_03]
     *       auto-on-start: false      # 标定需人工标注,默认不自动拉起
     *       block-cv-when-missing: true
     * </pre>
     */
    @Getter
    @Setter
    public static class Calibration {
        /** 编排总开关。关时状态接口仍可读(只查文件),但不会拉起标定进程 */
        private boolean enabled = false;
        /** python 可执行文件(建议与 enroll/cv 同一个 conda 环境) */
        private String pythonExecutable = "python";
        /** {@code run_live_ws.py} 相对 workDir 的路径 */
        private String scriptPath = "scripts/run_live_ws.py";
        /** 算法仓库根目录({@code PYTHONPATH=.} 相对此目录);null 用进程当前目录 */
        private String workDir;
        /** 标定产物根目录,相对 workDir(算法默认写 data/calibration) */
        private String outputRoot = "data/calibration";
        /** 产物子目录前缀:直播课产物为 {@code live_{session}},与离线 v2_4cam_zoned 隔离 */
        private String dirPrefix = "live_";
        /** 判定"已标定"必须存在的文件(相对产物目录) */
        private List<String> requiredFiles =
                new ArrayList<>(List.of("cameras.json", "camera_centers_world.json"));
        /** 必须各有一份 {cam}.json 的机位;留空则只校验 requiredFiles */
        private List<String> requiredCameras =
                new ArrayList<>(List.of("cam_01", "cam_02", "cam_03"));
        /**
         * 上课发现产物缺失时,是否自动拉起一次标定
         * 默认关:标定含 GUI 标注(annotate)这一步,需要人在算法机前点选控制点,
         * 后台自动拉起只会在无人值守的机器上弹一个没人操作的窗口,徒增一个僵死进程。
         * 正常流程是教师在算法机上标完,前端用 {@code /local/calibration/status} 查就绪
         */
        private boolean autoOnStart = false;
        /**
         * 标定未就绪时是否不启动 CV(录制照常)。
         * true=宁可不跑 CV 也不出不可信角度;false=照常跑,产出按未标定处理
         */
        private boolean blockCvWhenMissing = true;
        /** 追加到命令尾部的固定参数 */
        private List<String> extraArgs = new ArrayList<>();
        /** 注入子进程的环境变量;python 建议 PYTHONUNBUFFERED=1 */
        private Map<String, String> env = new LinkedHashMap<>();
        /** 单次标定墙钟上限。标定含抽帧+求解,给足时间 */
        private Duration timeout = Duration.ofMinutes(20);
    }

    /** 场边遥测:Java 日志 / Python 输出 / 进程运行数据 / WS 与系统指标 */
    @Getter
    @Setter
    public static class Telemetry {
        /** 总开关。关闭后不挂 Appender、不上报,但进程输出仍会打印到本地日志。 */
        private boolean enabled = true;
        /** 内存队列容量;满时丢弃最旧事件,优先保留最新异常。 */
        private int queueCapacity = 20_000;
        /** 单次上报最大事件数,云端接口限制 1000。 */
        private int batchSize = 500;
        /** 上报间隔。 */
        private Duration flushInterval = Duration.ofSeconds(5);
        /** 系统与 WS 指标采样间隔。 */
        private Duration metricInterval = Duration.ofSeconds(15);
        /** 是否采集 Java 日志。 */
        private boolean javaLogsEnabled = true;
        /** Java 日志最低级别。 */
        private String javaLogLevel = "INFO";
        /** 是否采集 Python stdout/stderr。 */
        private boolean pythonLogsEnabled = true;
        /** 单条 message 最大字符数。 */
        private int maxMessageChars = 8_000;
        /** 单条堆栈最大字符数。 */
        private int maxStackChars = 16_000;
    }

    /** 出云选路。批处理(session/clips/gallery)可走 MQ;实时反馈始终走 HTTP */
    @Getter
    @Setter
    public static class Ingest {
        private Mq mq = new Mq();

        /** 批处理出云 MQ(RabbitMQ)。关闭时 SessionCloudPublisher 走 HTTP(默认) */
        @Getter
        @Setter
        public static class Mq {
            private boolean enabled = false;
            /** 与云端 hoopshake.ingest 一致的 topic 交换机名。 */
            private String exchange = "hoopshake.ingest";
        }
    }

}
