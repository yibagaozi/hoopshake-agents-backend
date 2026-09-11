package com.cnsportiot.edge.cloudsync;

import java.nio.file.Path;

/**
 * 对象存储上传端口:把本地文件(逐帧 motion.jsonl / gallery 特征)推到中心存储(MinIO),
 * 返回可回填 {@code action_clip.motion_uri} / {@code reid_gallery.storage_uri} 的对象 URI。
 * 抽象成端口便于替换实现与脱机测试(默认 {@link MinioObjectStore};未配置时 {@link NoopObjectStore})。
 */
public interface ObjectStore {

    /**
     * 上传本地文件到给定对象键。
     *
     * @param objectKey   对象键(bucket 内路径,如 {@code sessions/{id}/motion.jsonl})
     * @param file        本地文件
     * @param contentType MIME 类型,可空
     * @return 存储结果(含可回填的 URI)
     */
    StoredObject put(String objectKey, Path file, String contentType);

    /** 上传结果。 */
    record StoredObject(String uri, long size) {}
}
