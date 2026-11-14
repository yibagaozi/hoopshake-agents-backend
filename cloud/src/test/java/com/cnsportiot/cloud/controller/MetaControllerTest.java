package com.cnsportiot.cloud.controller;

import com.cnsportiot.cloud.dto.response.MetaDtos.ActionVocab;
import com.cnsportiot.cloud.dto.response.MetaDtos.CheckpointVocab;
import com.cnsportiot.cloud.dto.response.MetaDtos.VocabularyResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 词表端点:动作/检查点 id 是权威的那套(点号 checkpoint id + 真实 action id),不含臆造占位。 */
class MetaControllerTest {

    @Test
    void vocabulary_returnsAuthoritativeIds_noPlaceholders() {
        VocabularyResponse v = new MetaController().vocabulary().data();

        assertThat(v.version()).isNotBlank();
        assertThat(v.actions()).extracting(ActionVocab::id)
                .contains("free_throw", "jump_shot", "layup", "triple_threat", "pass");
        assertThat(v.checkpoints()).extracting(CheckpointVocab::id)
                .contains("ft.release.elbow", "ft.load.knee", "js.release.elbow", "safety.layup_landing_knee");
        // 不含任何臆造/占位 id
        assertThat(v.checkpoints()).extracting(CheckpointVocab::id)
                .doesNotContain("release_elbow_extension", "elbow_alignment", "release_timing",
                        "knee_valgus", "follow_through", "jump_balance", "stance", "hand", "balance");
    }

    @Test
    void vocabulary_safetyFlagAndActionTypesCorrect() {
        VocabularyResponse v = new MetaController().vocabulary().data();

        CheckpointVocab landing = v.checkpoints().stream()
                .filter(c -> c.id().equals("safety.layup_landing_knee")).findFirst().orElseThrow();
        assertThat(landing.safety()).isTrue();
        assertThat(landing.actionTypes()).containsExactly("layup");

        CheckpointVocab elbow = v.checkpoints().stream()
                .filter(c -> c.id().equals("ft.release.elbow")).findFirst().orElseThrow();
        assertThat(elbow.safety()).isFalse();
        assertThat(elbow.label()).isEqualTo("出手肘伸展");
    }

    @Test
    void vocabulary_phasesMirrorAlgoSuperset() {
        VocabularyResponse v = new MetaController().vocabulary().data();

        // 相位序列与算法 action_phase_vocab.json 的 clip_by_action_type 一字不差
        assertThat(v.phases().byActionType())
                .containsKeys("free_throw", "jump_shot", "layup", "triple_threat", "pass");
        assertThat(v.phases().byActionType().get("free_throw"))
                .containsExactly("load", "set", "release", "follow_through");
        assertThat(v.phases().byActionType().get("jump_shot"))
                .containsExactly("load", "takeoff", "release", "follow_through");
        assertThat(v.phases().byActionType().get("layup"))
                .containsExactly("approach", "gather", "takeoff", "release", "finish");
        assertThat(v.phases().byActionType().get("triple_threat"))
                .containsExactly("load", "action", "recover");
        assertThat(v.phases().byActionType().get("pass"))
                .containsExactly("load", "action", "recover");

        // 相位中文名与算法 clip_labels_zh 一致
        assertThat(v.phases().labels())
                .containsEntry("load", "蓄力")
                .containsEntry("release", "出手")
                .containsEntry("takeoff", "起跳")
                .containsEntry("finish", "终结")
                .containsEntry("recover", "恢复");

        // 词表是算法的超集:算法所有 action 的所有 phase 都能在 labels 里找到中文名
        v.phases().byActionType().values().stream().flatMap(List::stream).distinct()
                .forEach(phase -> assertThat(v.phases().labels()).containsKey(phase));
    }
}
