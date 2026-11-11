package com.cnsportiot.edge.storage;

import com.cnsportiot.edge.config.EdgeProperties;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/** 磁盘空间与录像码率估算 */
public final class DiskSpace {

    public static final long GB = 1024L * 1024 * 1024;

    private DiskSpace() {
    }

    /** 可用空间;路径尚不存在时取最近的已存在上级目录(不存在的路径 getUsableSpace 恒为 0)。 */
    public static long usableBytes(Path path) {
        File f = path.toAbsolutePath().toFile();
        while (f != null && !f.exists()) {
            f = f.getParentFile();
        }
        return f == null ? 0 : f.getUsableSpace();
    }

    public static long totalBytes(Path path) {
        File f = path.toAbsolutePath().toFile();
        while (f != null && !f.exists()) {
            f = f.getParentFile();
        }
        return f == null ? 0 : f.getTotalSpace();
    }

    /** 录制时每秒写盘字节数估算:编码平均码率(encode.bitrate)× 已配置机位数;录制是 -c copy,码率即编码码率。 */
    public static long recordBytesPerSecond(EdgeProperties props) {
        long bits = parseBitrate(props.getEncode().getBitrate());
        int cams = Math.max(1, props.getCameras().size());
        return Math.max(1, bits / 8 * cams);
    }

    /** "8M" / "8000k" / "8000000" → bit/s;无法解析时按 8 Mbps。 */
    static long parseBitrate(String value) {
        if (value == null || value.isBlank()) {
            return 8_000_000L;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        long mul = 1;
        char last = v.charAt(v.length() - 1);
        if (last == 'K' || last == 'M' || last == 'G') {
            mul = last == 'K' ? 1_000L : last == 'M' ? 1_000_000L : 1_000_000_000L;
            v = v.substring(0, v.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(v) * mul);
        } catch (NumberFormatException e) {
            return 8_000_000L;
        }
    }
}
