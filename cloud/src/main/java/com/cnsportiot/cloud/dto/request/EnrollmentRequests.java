package com.cnsportiot.cloud.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 参课名单导入 */
public final class EnrollmentRequests {

    private EnrollmentRequests() {
    }

    /** 5.7 导入 / 5.11 预检共用 */
    public record ImportEnrollmentRequest(
            @NotEmpty(message = "名单不能为空")
            @Size(max = 500, message = "单次导入不超过 500 人")
            List<@Valid StudentEntry> students) {
    }

    /**
     * @param studentNo   10 位纯数字,无前导零(§1.2)
     * @param displayName 仅建档时使用;**对已存在的学生不生效** —— 导入不负责更新已有档案,
     *                    改名走 6.4。否则一份旧名单会把学生改过的姓名覆盖回去
     */
    public record StudentEntry(
            @Pattern(regexp = "^\\d{10}$", message = "学号须为 10 位数字")
            String studentNo,

            @Size(max = 64, message = "姓名不超过 64 字")
            String displayName) {

        /** 在校验之前先去掉首尾空白 */
        public StudentEntry {
            studentNo = studentNo == null ? null : studentNo.strip();
            displayName = (displayName == null || displayName.isBlank()) ? null : displayName.strip();
        }
    }
}

