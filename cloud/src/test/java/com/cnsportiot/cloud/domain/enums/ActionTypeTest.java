package com.cnsportiot.cloud.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ActionType 词表枚举解析与元信息(与 action_phase_vocab.json 对齐) */
class ActionTypeTest {

    @Test
    void fromValue_byVocabValue() {
        assertThat(ActionType.fromValue("free_throw")).contains(ActionType.FREE_THROW);
        assertThat(ActionType.fromValue("layup")).contains(ActionType.LAYUP);
        assertThat(ActionType.fromValue("triple_threat")).contains(ActionType.TRIPLE_THREAT);
    }

    @Test
    void fromValue_caseInsensitive_andEnumName() {
        assertThat(ActionType.fromValue("Free_Throw")).contains(ActionType.FREE_THROW);
        assertThat(ActionType.fromValue("JUMP_SHOT")).contains(ActionType.JUMP_SHOT);
        assertThat(ActionType.fromValue("  pass  ")).contains(ActionType.PASS);
    }

    @Test
    void fromValue_unknownOrBlank_isEmpty() {
        assertThat(ActionType.fromValue("dunk")).isEmpty();
        assertThat(ActionType.fromValue("")).isEmpty();
        assertThat(ActionType.fromValue(null)).isEmpty();
    }

    @Test
    void storedValueIsVocabValue_notEnumName() {
        assertThat(ActionType.FREE_THROW.getValue()).isEqualTo("free_throw");
        assertThat(ActionType.UNKNOWN.getValue()).isEqualTo("unknown");
    }

    @Test
    void familyMetadata_matchesVocab() {
        assertThat(ActionType.FREE_THROW.isShooting()).isTrue();
        assertThat(ActionType.JUMP_SHOT.isShooting()).isTrue();
        assertThat(ActionType.LAYUP.isShooting()).isTrue();
        assertThat(ActionType.TRIPLE_THREAT.isShooting()).isFalse();
        assertThat(ActionType.PASS.isShooting()).isFalse();

        assertThat(ActionType.FREE_THROW.hasMakeMiss()).isTrue();
        assertThat(ActionType.TRIPLE_THREAT.hasMakeMiss()).isFalse();
    }

    @Test
    void allowedValues_listsAll() {
        String allowed = ActionType.allowedValues();
        assertThat(allowed).contains("free_throw", "jump_shot", "layup", "triple_threat", "pass", "unknown");
    }
}
