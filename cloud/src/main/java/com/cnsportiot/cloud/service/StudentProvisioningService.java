package com.cnsportiot.cloud.service;

import java.util.UUID;

public interface StudentProvisioningService {

    /**
     * 按学号建档:创建 account + student 两条记录
     *
     * <p>约定(5.7):{@code username = studentNo}、初始密码走策略、
     * {@code status = PENDING_ACTIVATION}、
     * {@code dominantHand}/{@code heightCm}/{@code legLengthCm}/{@code gradeBand} 全部留空
     * 后三项是 CV 评分的输入参数,由学生经 4.9 自维护。
     *
     * <p>**必须在调用方的事务内执行**(不要标 REQUIRES_NEW):批量导入中途失败时
     * 已建的账号要跟着回滚,否则教师重试会撞上一批"账号已存在但没报名"的半成品。
     *
     * @param displayName 姓名,可为 null
     * @return 新建的 studentId
     */
    UUID provisionByStudentNo(String studentNo, String displayName);
}
