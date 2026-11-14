package com.cnsportiot.edge.rules;

import lombok.Getter;
import lombok.Setter;

/**
 * 实时 2D 规则引擎的**检查点词表**条目(一条 = 某动作某相位的一个可评量)。
 *
 * <p>语义:命中相位后,从 {@code ActionSample.measured} 取 {@link #metric} 的值,与 [{@link #min},{@link #max}]
 * 达标带比较——低于下界播 {@link #cueLow}、高于上界播 {@link #cueHigh}、在带内且配了 {@link #cueOk} 则播正反馈。
 * {@link #safety}=true 的条目命中即安全告警。词表是**教学话术 + 阈值**的单一事实源,产品/教练可迭代,不改代码。
 *
 * <p>与算法侧批处理 YAML(configs/actions/*.yaml)口径一致,但这里是**单机位 2D 实时**变体:
 * 只放 COCO-17 可算的量(肘/膝角、出手高度、躯干前倾…),不放需手部关键点的腕屈角。
 */
@Getter
@Setter
public class CheckpointRule {

    /** 稳定唯一 id,如 {@code ft.release.elbow};入 instant_feedback.checkpoint_id。 */
    private String id;

    /** 适用动作类型;{@code "*"} 通配。 */
    private String actionType;

    /** 适用相位(load/set/release/follow_through/takeoff/approach/gather/finish/action/recover);{@code "*"} 通配。 */
    private String phase;

    /** 取值键:对应 {@code ActionSample.measured} 里的字段名。 */
    private String metric;

    /** 达标带下界(含);null = 不设下界。 */
    private Double min;

    /** 达标带上界(含);null = 不设上界。 */
    private Double max;

    private String unit = "deg";

    /** 超带时的严重度:MINOR / MAJOR(POSITIVE 保留给达标反馈,不在此配)。 */
    private String severity = "MAJOR";

    /** 是否安全项:命中即走 SafetyAlert(大屏高亮 + 不轻易抑制)。 */
    private boolean safety = false;

    /** 供批处理/汇总参考,实时不强用。 */
    private Double weight;

    /** 大屏短标签,如"出手肘角"。 */
    private String checkpointLabel;

    /** metric < min 的话术。 */
    private String cueLow;

    /** metric > max 的话术。 */
    private String cueHigh;

    /** 在达标带内的正反馈话术;为空则不播正反馈。 */
    private String cueOk;

    /** 同(学生,检查点)最小推送间隔(ms);null = 用全局默认。防刷屏。 */
    private Long cooldownMs;

    /** 低于此身份/姿态置信不推;null = 用全局默认。 */
    private Double minConfidence;

    private boolean enabled = true;
}
