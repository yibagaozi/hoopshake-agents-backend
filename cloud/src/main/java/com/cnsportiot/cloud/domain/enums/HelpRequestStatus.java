package com.cnsportiot.cloud.domain.enums;

/** 学生请求教师协助工单状态 */
public enum HelpRequestStatus {
    PENDING,    // 学生已发起,待教师查看
    VIEWED,     // 教师已查看
    RESOLVED,   // 教师已答复/处理
    DISMISSED   // 教师忽略/关闭
}
