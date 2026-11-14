package com.cnsportiot.cloud.dto.request;

import com.cnsportiot.contracts.enums.DominantHand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

/** 学生训练数据请求 DTO */
public final class StudentDataRequests {
    private StudentDataRequests() {}

    /** PUT /api/student/profile 学生自助改个人基线 */
    public record UpdateProfileRequest(
            DominantHand dominantHand,
            @DecimalMin("0.0") @Digits(integer = 4, fraction = 1) BigDecimal heightCm,
            @DecimalMin("0.0") @Digits(integer = 4, fraction = 1) BigDecimal legLengthCm) {}
}
