package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.config.EdgeProperties;
import com.cnsportiot.edge.exception.EdgeErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MinIO(S3 兼容)对象存储实现。仅当 {@code hoopshake.edge.minio.enabled=true} 装配;
 * 否则由 {@link NoopObjectStore} 兜底(开发/无中心存储时不阻塞出云的其余步骤)。
 * 首次上传前惰性建桶(幂等)。回填 URI 按 {@code uri-scheme} 取 {@code s3://} 或可访问 URL。
 */
@Component
@ConditionalOnProperty(prefix = "hoopshake.edge.minio", name = "enabled", havingValue = "true")
public class MinioObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStore.class);

    private final EdgeProperties.Minio cfg;
    private final MinioClient client;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioObjectStore(EdgeProperties props) {
        this.cfg = props.getMinio();
        this.client = MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .build();
        log.info("MinIO 对象存储已启用 endpoint={} bucket={}", cfg.getEndpoint(), cfg.getBucket());
    }

    @Override
    public StoredObject put(String objectKey, Path file, String contentType) {
        ensureBucket();
        try {
            UploadObjectArgs.Builder b = UploadObjectArgs.builder()
                    .bucket(cfg.getBucket())
                    .object(objectKey)
                    .filename(file.toString());
            if (contentType != null && !contentType.isBlank()) {
                b.contentType(contentType);
            }
            client.uploadObject(b.build());
            long size = java.nio.file.Files.size(file);
            log.info("已上传对象 bucket={} key={} size={}", cfg.getBucket(), objectKey, size);
            return new StoredObject(uriOf(objectKey), size);
        } catch (Exception e) {
            throw new BusinessException(EdgeErrorCode.STORAGE_FAILED, "上传失败 " + objectKey + ": " + e.getMessage());
        }
    }

    private void ensureBucket() {
        if (bucketReady.get()) {
            return;
        }
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(cfg.getBucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(cfg.getBucket()).build());
                log.info("已创建 bucket {}", cfg.getBucket());
            }
            bucketReady.set(true);
        } catch (Exception e) {
            throw new BusinessException(EdgeErrorCode.STORAGE_FAILED, "确认/创建 bucket 失败: " + e.getMessage());
        }
    }

    /** 回填 URI:s3 方案返回不透明 {@code s3://bucket/key};url 方案返回可拉取地址。 */
    private String uriOf(String objectKey) {
        if ("url".equalsIgnoreCase(cfg.getUriScheme())) {
            String base = (cfg.getPublicBaseUrl() != null && !cfg.getPublicBaseUrl().isBlank())
                    ? cfg.getPublicBaseUrl() : cfg.getEndpoint();
            return trimTrailingSlash(base) + "/" + cfg.getBucket() + "/" + objectKey;
        }
        return "s3://" + cfg.getBucket() + "/" + objectKey;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
