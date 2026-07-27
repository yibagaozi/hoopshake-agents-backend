package com.cnsportiot.edge.service.impl;

import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.edge.service.EnrollmentService;
import com.cnsportiot.edge.dto.EnrollmentDtos.EnrollProgressResponse;
import com.cnsportiot.edge.dto.EnrollmentDtos.EnrollTaskResponse;
import com.cnsportiot.edge.dto.EnrollmentDtos.StartEnrollRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 姿态特征采集 */
@Service
public class EnrollmentServiceImpl implements EnrollmentService {

    @Override
    public EnrollTaskResponse start(StartEnrollRequest request) {
        throw BusinessException.notImplemented();
    }

    @Override
    public EnrollProgressResponse progress(UUID taskId) {
        throw BusinessException.notImplemented();
    }
}

