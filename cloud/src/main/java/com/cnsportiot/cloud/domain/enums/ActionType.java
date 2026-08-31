package com.cnsportiot.cloud.domain.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 动作类型与算法侧 {@code action_phase_vocab.json} 的 {@code action_types} 对齐(见词表)
 * {@link #value} 是与算法/向量库 metadata 一致的小写标识(如 {@code free_throw}),
 * 落库与 chunk metadata 一律用它,保证"诊断 → checkpoint → 按 action_type 精准召回"跨系统可对齐
 * 其余字段携带词表元信息(中文名 / family / 是否有出手时刻 / 是否有命中判定)
 */
public enum ActionType {

    FREE_THROW("free_throw", "罚篮", Family.SHOOTING, true, true),
    JUMP_SHOT("jump_shot", "跳投", Family.SHOOTING, true, true),
    LAYUP("layup", "上篮", Family.SHOOTING, true, true),
    TRIPLE_THREAT("triple_threat", "突破", Family.POSE_ONLY, false, false),
    PASS("pass", "传球", Family.POSE_ONLY, false, false),
    UNKNOWN("unknown", "未知", null, false, false);

    public enum Family { SHOOTING, POSE_ONLY }

    @Getter
    private final String value;
    @Getter
    private final String labelZh;
    @Getter
    private final Family family;
    private final boolean hasReleaseMs;
    private final boolean hasMakeMiss;

    ActionType(String value, String labelZh, Family family, boolean hasReleaseMs, boolean hasMakeMiss) {
        this.value = value;
        this.labelZh = labelZh;
        this.family = family;
        this.hasReleaseMs = hasReleaseMs;
        this.hasMakeMiss = hasMakeMiss;
    }

    public boolean hasReleaseMs() {
        return hasReleaseMs;
    }

    public boolean hasMakeMiss() {
        return hasMakeMiss;
    }

    public boolean isShooting() {
        return family == Family.SHOOTING;
    }

    /** 按词表 value(不区分大小写)解析;也兼容枚举名。未知返回空 */
    public static Optional<ActionType> fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(a -> a.value.equals(v) || a.name().toLowerCase(Locale.ROOT).equals(v))
                .findFirst();
    }

    /** 逗号分隔的合法 value 列表,供错误提示 */
    public static String allowedValues() {
        return Arrays.stream(values()).map(a -> a.value).collect(Collectors.joining(", "));
    }
}

