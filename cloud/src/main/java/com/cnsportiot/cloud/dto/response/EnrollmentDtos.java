package com.cnsportiot.cloud.dto.response;

import com.cnsportiot.cloud.domain.enums.AccountStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 参课名单出参 */
public final class EnrollmentDtos {

    private EnrollmentDtos() {
    }

    /**
     * 名单条目
     *
     * @param accountStatus 账号是否激活,影响学生能否登录看数据
     * @param galleryReady  ReID 特征是否就绪,影响课上能否识别
     * @param justCreated   仅本次导入新建的为 true。这是响应上下文字段而非持久化字段
     */
    public record EnrollmentItem(
            UUID studentId,
            String studentNo,
            String displayName,
            AccountStatus accountStatus,
            boolean galleryReady,
            boolean justCreated,
            OffsetDateTime enrolledAt) {

        public EnrollmentItem withJustCreated(boolean created) {
            return new EnrollmentItem(studentId, studentNo, displayName,
                    accountStatus, galleryReady, created, enrolledAt);
        }
    }

    /**
     * 5.11 预检结果
     *
     * <p>四组互斥,合计 = 去重后的请求条数。教师提交前据此确认影响面,
     * 避免学号录错后逐个回删 —— 尤其 {@code willCreate}
     */
    public record ImportPreviewResponse(
            List<PreviewItem> willCreate,
            List<PreviewItem> willEnroll,
            List<PreviewItem> alreadyEnrolled,
            List<InvalidItem> invalid) {

        public int total() {
            return willCreate.size() + willEnroll.size() + alreadyEnrolled.size() + invalid.size();
        }
    }

    /**
     * @param displayName {@code willCreate} 取请求里给的名字
     * @param studentId   {@code willCreate} 为 null,该生尚不存在
     */
    public record PreviewItem(
            String studentNo,
            String displayName,
            UUID studentId) {

        public static PreviewItem toCreate(String studentNo, String displayName) {
            return new PreviewItem(studentNo, displayName, null);
        }
    }

    public record InvalidItem(
            String studentNo,
            String reason) {
    }
}

