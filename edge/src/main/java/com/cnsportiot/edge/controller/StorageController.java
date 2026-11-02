package com.cnsportiot.edge.controller;

import com.cnsportiot.contracts.common.ApiResponse;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import com.cnsportiot.edge.dto.StorageDtos.CleanupResult;
import com.cnsportiot.edge.dto.StorageDtos.SessionStorage;
import com.cnsportiot.edge.dto.StorageDtos.StorageStatus;
import com.cnsportiot.edge.storage.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 录像存储:占用统计、按保留天数清理、保留标记。
 *
 * <p>只删除各会话 meta.json 中列出的录像文件;进行中/批处理中/KEEP/未出云(requirePublished)的会话不删。
 */
@RestController
@RequestMapping("/local/storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /** 磁盘与各会话录像占用;带 retentionDays 时预览按该天数可清理哪些(不删除) */
    @GetMapping
    public ApiResponse<StorageStatus> status(@RequestParam(required = false) Integer retentionDays) {
        return ApiResponse.ok(storageService.status(retentionDays));
    }

    /** 立即清理一次;retentionDays 缺省用配置 storage.raw-retention-days,两者都 ≤0 时报错 */
    @PostMapping("/cleanup")
    public ApiResponse<CleanupResult> cleanup(@RequestParam(required = false) Integer retentionDays) {
        int days = retentionDays != null ? retentionDays : storageService.configuredRetentionDays();
        if (days <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "未配置保留天数:传 retentionDays=N,或配置 hoopshake.edge.storage.raw-retention-days");
        }
        return ApiResponse.ok(storageService.cleanup("manual", days));
    }

    /** 标记/取消保留(KEEP),被标记的会话永不自动清理 */
    @PutMapping("/sessions/{sessionId}/keep")
    public ApiResponse<SessionStorage> keep(@PathVariable UUID sessionId,
                                            @RequestParam(defaultValue = "true") boolean keep) {
        return ApiResponse.ok(storageService.setKeep(sessionId, keep));
    }
}
