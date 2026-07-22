package com.cnsportiot.cloud.dto.request;

import com.cnsportiot.cloud.domain.enums.DominantHand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 学生管理请求 DTO */
public final class StudentManageRequests {
    private StudentManageRequests() {}

    /** 6.1 学生建档(账号 + 档案同事务) */
    public record RegisterStudentRequest(
            @NotBlank @Size(max = 32) String studentNo,
            @NotBlank @Size(max = 64) String displayName,
            @Size(max = 64) String username,
            @Size(min = 6, max = 64) String password,
            @Email @Size(max = 128) String email,
            @Size(max = 32) String phone,
            DominantHand dominantHand,
            @DecimalMin("0.0") @Digits(integer = 4, fraction = 1) BigDecimal heightCm,
            @DecimalMin("0.0") @Digits(integer = 4, fraction = 1) BigDecimal legLengthCm,
            @Size(max = 16) String gradeBand) {}

    /** 6.4 更新档案(字段均可选) */
    public record UpdateStudentRequest(
            @Size(max = 64) String displayName,
            DominantHand dominantHand,
            @DecimalMin("0.0") @Digits(integer = 4, fraction = 1) BigDecimal heightCm,
            @DecimalMin("0.0") @Digits(integer = 4, fraction = 1) BigDecimal legLengthCm,
            @Size(max = 16) String gradeBand) {}
}
