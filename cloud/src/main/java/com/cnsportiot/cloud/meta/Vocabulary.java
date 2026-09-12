package com.cnsportiot.cloud.meta;

import com.cnsportiot.cloud.dto.response.MetaDtos.ActionVocab;
import com.cnsportiot.cloud.dto.response.MetaDtos.CheckpointVocab;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权威词表(动作类型 + 检查点),供 {@code GET /api/meta/vocabulary} 与课程 {@code enabledCheckpoints} 校验共用。
 * 检查点 id 就是场边 {@code edge/checkpoints.yaml} 里的点号 id(如 {@code ft.release.elbow})——
 * 那是全系统唯一真正会触发提示、并落进 {@code instant_feedback.checkpoint_id} 的那套命名
 */
public final class Vocabulary {

    private Vocabulary() {
    }

    /** 词表版本,前端可据此判断是否需要刷新缓存 */
    public static final String VERSION = "2026-09-12";

    /** 动作类型(与 {@code ActionType} 枚举、算法 action_phase_vocab 对齐) */
    public static final List<ActionVocab> ACTIONS = List.of(
            new ActionVocab("free_throw", "罚篮", List.of("cam_03")),
            new ActionVocab("jump_shot", "跳投", List.of("cam_01", "cam_02", "cam_03")),
            new ActionVocab("layup", "上篮", List.of("cam_03")),
            new ActionVocab("triple_threat", "突破", List.of("cam_01", "cam_02")),
            new ActionVocab("pass", "传球", List.of("cam_01", "cam_02")));

    /** 检查点(id 与 edge/checkpoints.yaml 完全一致) */
    public static final List<CheckpointVocab> CHECKPOINTS = List.of(
            new CheckpointVocab("ft.load.knee", "蓄力屈膝", false, List.of("free_throw")),
            new CheckpointVocab("ft.set.elbow", "设定点肘位", false, List.of("free_throw")),
            new CheckpointVocab("ft.release.elbow", "出手肘伸展", false, List.of("free_throw")),
            new CheckpointVocab("ft.release.wrist", "出手压腕", false, List.of("free_throw")),
            new CheckpointVocab("ft.follow.elbow", "跟随伸展", false, List.of("free_throw")),
            new CheckpointVocab("js.load.knee", "起跳蓄力", false, List.of("jump_shot")),
            new CheckpointVocab("js.release.elbow", "跳投出手肘", false, List.of("jump_shot")),
            new CheckpointVocab("js.release.wrist", "跳投压腕", false, List.of("jump_shot")),
            new CheckpointVocab("js.follow.elbow", "跳投跟随", false, List.of("jump_shot")),
            new CheckpointVocab("lu.takeoff.knee", "上篮起跳蹬伸", false, List.of("layup")),
            new CheckpointVocab("lu.finish.elbow", "上篮终结伸展", false, List.of("layup")),
            new CheckpointVocab("tt.load.knee", "三威胁重心", false, List.of("triple_threat")),
            new CheckpointVocab("safety.layup_landing_knee", "落地屈膝缓冲", true, List.of("layup")));

    /**
     * 每个动作的相位序列(与算法 action_phase_vocab.json 的 clip_by_action_type 一字不差)。
     * 相位是算法切片的粒度,检查点(上面的 CHECKPOINTS)是叠加在相位上的教学评价点
     */
    public static final Map<String, List<String>> PHASES_BY_ACTION = Map.of(
            "free_throw", List.of("load", "set", "release", "follow_through"),
            "jump_shot", List.of("load", "takeoff", "release", "follow_through"),
            "layup", List.of("approach", "gather", "takeoff", "release", "finish"),
            "triple_threat", List.of("load", "action", "recover"),
            "pass", List.of("load", "action", "recover"));

    /** 相位中文名(与算法 clip_labels_zh 一致) */
    public static final Map<String, String> PHASE_LABELS = Map.ofEntries(
            Map.entry("load", "蓄力"),
            Map.entry("set", "设定点"),
            Map.entry("takeoff", "起跳"),
            Map.entry("release", "出手"),
            Map.entry("follow_through", "跟随"),
            Map.entry("approach", "起步"),
            Map.entry("gather", "收球"),
            Map.entry("finish", "终结"),
            Map.entry("action", "动作"),
            Map.entry("recover", "恢复"));

    /** 合法动作类型 id 集合(课程 actionTypes 校验用) */
    public static final Set<String> ACTION_IDS =
            ACTIONS.stream().map(ActionVocab::id).collect(Collectors.toUnmodifiableSet());

    /** 合法检查点 id 集合(课程 enabledCheckpoints 校验用) */
    public static final Set<String> CHECKPOINT_IDS =
            CHECKPOINTS.stream().map(CheckpointVocab::id).collect(Collectors.toUnmodifiableSet());
}
