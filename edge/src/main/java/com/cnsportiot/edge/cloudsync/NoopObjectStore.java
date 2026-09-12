package com.cnsportiot.edge.cloudsync;

import com.cnsportiot.edge.config.EdgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 兜底对象存储:MinIO 未启用时装配。不真正上传,只返回一个不透明 {@code s3://} 占位 URI 并告警——
 * 让"会话结束出云"的其余步骤(session/clip/gallery 元数据)照常进行,逐帧文件回头补传。
 */
@Configuration
public class NoopObjectStore {

    private static final Logger log = LoggerFactory.getLogger(NoopObjectStore.class);

    // 方法名(= bean 名)必须与本 @Configuration 类的默认 bean 名(noopObjectStore,由类名首字母小写而来)
    // 区分开,否则二者同名触发 BeanDefinitionOverrideException(Boot 默认不允许覆盖),边缘整个上不来。
    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    public ObjectStore fallbackObjectStore(EdgeProperties props) {
        String bucket = props.getMinio().getBucket();
        log.warn("MinIO 未启用(hoopshake.edge.minio.enabled=false):逐帧文件不会上传,motion_uri 仅占位");
        return (objectKey, file, contentType) -> {
            long size = safeSize(file);
            log.warn("跳过上传(NoopObjectStore)key={} size={}", objectKey, size);
            return new ObjectStore.StoredObject("s3://" + bucket + "/" + objectKey, size);
        };
    }

    private static long safeSize(Path file) {
        try {
            return java.nio.file.Files.size(file);
        } catch (Exception e) {
            return -1;
        }
    }
}
