package com.cnsportiot.edge.service;

import com.cnsportiot.edge.dto.RosterDtos.RosterResponse;
import com.cnsportiot.edge.dto.RosterDtos.MatchResponse;

import java.util.UUID;

/** 参课名单:从云端 §10.5 拉取并缓存在本地,断网时仍可读(架构规划 E3) */
public interface RosterService {

    /** 从云端重新拉取指定课程的名单与 gallery 引用 */
    RosterResponse sync(UUID lessonId);

    /** 读本地缓存;未拉取过则 ROSTER_NOT_LOADED */
    RosterResponse current();

    /** 按完整学号精确匹配,供现场注册判断该生是否在本课名单内 */
    MatchResponse match(String studentNo);
}
