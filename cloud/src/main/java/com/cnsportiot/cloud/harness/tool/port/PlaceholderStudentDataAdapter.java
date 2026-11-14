package com.cnsportiot.cloud.harness.tool.port;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link StudentDataPort} 的占位实现
 * 数据全部由 {@code studentId} 确定性派生,因此:
 * 可重复:同一学生每次结果一致,便于测试;
 * 归属可判:{@link #owns} 认得自己派生出来的 {@code trainingSessionId}(seed 集合),
 * 陌生/他人的 id 一律判非本人 → 触发越权闸门。故用 {@link #recentClips} 拿到的 id 调 SessionSummary 可通过
 */
@Component
public class PlaceholderStudentDataAdapter implements StudentDataPort {

    private static final String NOTE = "placeholder";
    /** 每个学生的种子会话数;id = seedSessionId(studentId, 0..SEED_COUNT-1)。 */
    private static final int SEED_COUNT = 3;
    private static final String[] ACTIONS = {"原地罚篮", "三步上篮", "急停跳投"};

    private UUID seedSessionId(UUID studentId, int i) {
        return UUID.nameUUIDFromBytes(("hs-session:" + studentId + ":" + i).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean owns(UUID studentId, ResourceType type, String resourceId) {
        if (studentId == null || resourceId == null || resourceId.isBlank()) {
            return false;
        }
        UUID rid;
        try {
            rid = UUID.fromString(resourceId.trim());
        } catch (IllegalArgumentException e) {
            return false;   // 非法 UUID 直接判非本人
        }
        for (int i = 0; i < SEED_COUNT; i++) {
            if (seedSessionId(studentId, i).equals(rid)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ClipSummary> recentClips(UUID studentId, int limit) {
        int n = Math.min(Math.max(limit, 1), SEED_COUNT);
        List<ClipSummary> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int made = 6 + i, total = 10;
            out.add(new ClipSummary(
                    seedSessionId(studentId, i), ACTIONS[i % ACTIONS.length],
                    "2026-08-" + String.format("%02d", 10 + i) + "T18:0" + i + ":00+08:00",
                    made, total, i == 0 ? "出手时机偏早" : "辅助手参与发力", NOTE));
        }
        return out;
    }

    @Override
    public SessionSummary sessionSummary(UUID studentId, UUID trainingSessionId) {
        UUID sid = trainingSessionId != null ? trainingSessionId : seedSessionId(studentId, 0);
        return new SessionSummary(
                sid, ACTIONS[0], "2026-08-10T18:00:00+08:00", 7, 10,
                List.of("命中率 70%,较上次 +10%", "出手弧度稳定", "非投篮手偶有推球"),
                "整体在进步,重点盯住出手时机;和自己比,别和别人比。", NOTE);
    }

    @Override
    public List<FeedbackEntry> instantFeedbackLog(UUID studentId, UUID trainingSessionId) {
        return List.of(
                new FeedbackEntry("00:12", "出手时机", "出手略早,起跳最高点再出手", "info", NOTE),
                new FeedbackEntry("00:37", "辅助手", "非投篮手拇指有推球,注意只扶不推", "warn", NOTE),
                new FeedbackEntry("01:05", "跟随动作", "手腕下压充分,保持", "info", NOTE));
    }

    @Override
    public ProgressTrend progressTrend(UUID studentId, String metric, int weeks) {
        String m = (metric == null || metric.isBlank()) ? "命中率" : metric.trim();
        int w = Math.min(Math.max(weeks, 1), 8);
        List<ProgressTrend.TrendPoint> pts = new ArrayList<>(w);
        for (int i = 0; i < w; i++) {
            pts.add(new ProgressTrend.TrendPoint("第" + (i + 1) + "周", 0.55 + 0.03 * i));
        }
        return new ProgressTrend(m, "近" + w + "周", pts, m + "稳步上升,保持训练节奏。", NOTE);
    }

    @Override
    public ActionDetail actionDetail(UUID studentId, String actionKey) {
        String key = (actionKey == null || actionKey.isBlank()) ? "freethrow" : actionKey.trim();
        return new ActionDetail(
                key, "原地罚篮要点",
                List.of("双脚与肩同宽,投篮脚略前", "屈膝蹬地,力量自下而上", "出手点在额前上方,手腕下压跟随"),
                List.of("出手时机偏早", "辅助手推球致侧旋", "身体后仰导致力量不足"), NOTE);
    }
}

