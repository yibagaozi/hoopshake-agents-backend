package com.cnsportiot.edge.service;

import com.cnsportiot.edge.domain.LessonContext;
import com.cnsportiot.edge.dto.LessonDtos.LessonContextResponse;
import com.cnsportiot.edge.dto.LessonDtos.LessonSelectResponse;
import com.cnsportiot.edge.dto.LessonDtos.SelectLessonRequest;

import java.util.Optional;

/** 选课上下文:教师上课前在操作台选定课程,edge 据此拉名单并进入 READY */
public interface LessonContextService {

    /** 选课:保存课程配置 → 拉取参课名单 → 会话进入 READY */
    LessonSelectResponse select(SelectLessonRequest request);

    /** 当前选定课程;未选课返回空对象而非报错,便于前端首屏渲染 */
    LessonContextResponse current();

    /** 供 SessionService 判断是否已选课 */
    Optional<LessonContext> context();

    /** 清空选课(下课收尾或换课时调用) */
    void clear();
}
