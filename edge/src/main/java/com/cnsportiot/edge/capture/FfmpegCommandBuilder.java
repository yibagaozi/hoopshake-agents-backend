package com.cnsportiot.edge.capture;

import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.domain.CameraDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** ffmpeg 命令行构造。采集与录制拆成两个进程, 录制端从 RTSP 拉流而非直连设备 */
@Component
public class FfmpegCommandBuilder {

    private final EdgeProperties props;

    public FfmpegCommandBuilder(EdgeProperties props) {
        this.props = props;
    }

    /** 采集进程:设备 → 编码 → 推流,附带黑屏检测分支 */
    public List<String> capture(CameraDescriptor cam) {
        EdgeProperties.Encode enc = props.getEncode();
        List<String> cmd = new ArrayList<>();

        cmd.add(props.getFfmpegPath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("info");

        // ── 输入:采集卡
        cmd.add("-f");
        cmd.add(inputFormat());
        cmd.add("-rtbufsize");
        cmd.add(enc.getRtbufsize());
        cmd.add("-video_size");
        cmd.add(enc.getVideoSize());
        cmd.add("-framerate");
        cmd.add(String.valueOf(enc.getFramerate()));
        cmd.add("-i");
        cmd.add(inputSpec(cam));

        // ── 输出一:编码后推 RTSP
        cmd.add("-map");
        cmd.add("0:v");
        cmd.addAll(encodeArgs(enc));
        cmd.add("-f");
        cmd.add("rtsp");
        cmd.add("-rtsp_transport");
        cmd.add("tcp");
        cmd.add(rtspUrl(cam.camId()));

        // ── 输出二:1fps 解码分支,仅供 blackdetect;
        //    黑屏检测是滤镜,需解码帧,无法挂在 -c copy 路径上
        cmd.add("-map");
        cmd.add("0:v");
        cmd.add("-vf");
        cmd.add("fps=1,blackdetect=d=2:pic_th=0.98");
        cmd.add("-f");
        cmd.add("null");
        cmd.add("-");

        // ── 进度输出到 stdout,日志留在 stderr,两个 reader 各读各的
        cmd.add("-progress");
        cmd.add("pipe:1");
        cmd.add("-nostats");

        return cmd;
    }

    /** 录制进程:从 RTSP 拉流直存,不再编码 */
    public List<String> record(String camId, String outputFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getFfmpegPath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("warning");
        cmd.add("-rtsp_transport");
        cmd.add("tcp");
        cmd.add("-i");
        cmd.add(rtspUrl(camId));
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-f");
        cmd.add("matroska");
        cmd.add(outputFile);
        return cmd;
    }

    private List<String> encodeArgs(EdgeProperties.Encode enc) {
        return List.of(
                "-c:v", enc.getCodec(),
                "-preset", enc.getPreset(),
                "-rc", "vbr",
                "-cq", String.valueOf(enc.getCq()),
                "-b:v", enc.getBitrate(),
                "-maxrate", enc.getMaxrate(),
                "-g", String.valueOf(enc.getGop()),
                "-pix_fmt", enc.getPixFmt());
    }

    public String rtspUrl(String camId) {
        return props.getMediamtx().getRtspBase() + "/" + camId;
    }

    private String inputFormat() {
        return isWindows() ? "dshow" : "v4l2";
    }

    /** Windows 的 dshow 需要 {@code video=设备名} 前缀 */
    private String inputSpec(CameraDescriptor cam) {
        return isWindows() ? "video=" + cam.deviceName() : cam.deviceName();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

