package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.dto.response.MetaDtos.PhasesVocab;
import com.cnsportiot.cloud.dto.response.MetaDtos.VocabularyResponse;
import com.cnsportiot.cloud.meta.Vocabulary;
import com.cnsportiot.contracts.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元数据/词表。权威动作类型 + 检查点词表,前端挂载时拉一次即可拿到 id→中文名→safety→适用动作
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    /** 12.1 词表:actions + phases(算法相位)+ checkpoints(教学叠加),含 version 便于前端判断刷新 */
    @GetMapping("/vocabulary")
    public ApiResponse<VocabularyResponse> vocabulary() {
        return ApiResponse.ok(new VocabularyResponse(
                Vocabulary.VERSION, Vocabulary.ACTIONS, Vocabulary.CHECKPOINTS,
                new PhasesVocab(Vocabulary.PHASES_BY_ACTION, Vocabulary.PHASE_LABELS)));
    }
}
