package com.cnsportiot.edge.service;

import com.cnsportiot.edge.dto.EnrollmentDtos.EnrollProgressResponse;
import com.cnsportiot.edge.dto.EnrollmentDtos.EnrollTaskResponse;
import com.cnsportiot.edge.dto.EnrollmentDtos.StartEnrollRequest;

import java.util.UUID;

/**
 * 现场姿态特征采集 */
public interface EnrollmentService {

    /** 启动采集任务 */
    EnrollTaskResponse start(StartEnrollRequest request);

    /** 查询采集进度 */
    EnrollProgressResponse progress(UUID taskId);
}
