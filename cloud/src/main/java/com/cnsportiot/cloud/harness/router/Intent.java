package com.cnsportiot.cloud.harness.router;

/**
 * 学生对话的意图分类
 * 决定档位、是否召回 RAG、是否开放工具、人设口吻
 */
public enum Intent {

    /** 复盘/我的训练/进步/上次表现——偏个人训练数据,倾向开工具、结构化召回、主回答档 */
    TRAINING_REVIEW,

    /** 动作要点/怎么做/技术姿势——偏动作讲解,召回 + 动作要点工具 */
    ACTION_TECHNIQUE,

    /** 训练原理/规则/篮球知识问答——偏知识,召回优先,轻量档 */
    KNOWLEDGE_QA,

    /** 问候/闲聊/自我介绍——不召回、不开工具,轻量档 */
    SMALLTALK,

    /** 与篮球训练无关——礼貌收敛,不召回、不开工具 */
    OUT_OF_SCOPE,

    /** 规则与分类都拿不准时的兜底——按最通用策略处理 */
    GENERAL
}
