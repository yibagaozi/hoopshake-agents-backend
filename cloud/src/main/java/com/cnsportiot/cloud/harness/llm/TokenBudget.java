package com.cnsportiot.cloud.harness.llm;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.contracts.error.BusinessException;
import com.cnsportiot.contracts.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 会话 Token 预算执行:在上下文预算内裁剪历史(丢最旧),优先保住 system + 本轮 user + 输出预留
 * 裁剪顺序:RAG 已在上游按档位限量注入进 system,这里只裁历史轮次;从最新往回保留能装下的,超出丢弃
 * 若 system + user + 预留已超预算(极端长输入) 抛 {@code TOKEN_BUDGET_EXCEEDED(42910)}
 */
@Component
public class TokenBudget {

    /** 每条消息的结构性开销(role/分隔符等)的粗略常量 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    private final AgentProperties props;

    public TokenBudget(AgentProperties props) {
        this.props = props;
    }

    /**
     * 在预算内裁剪历史
     *
     * @param system         系统提示(含已注入的 RAG)
     * @param user           本轮用户输入
     * @param history        历史轮次(正序:旧→新)
     * @param maxOutputTokens 本轮输出预留(null 用配置默认)
     * @return 可安全提交的历史(正序),可能比入参短
     */
    public List<LlmGateway.Turn> fitHistory(String system, String user,
                                            List<LlmGateway.Turn> history, Integer maxOutputTokens) {
        AgentProperties.Budget b = props.getBudget();
        int reserve = maxOutputTokens != null ? maxOutputTokens : b.getReserveOutputTokens();
        int base = TokenEstimator.estimate(system) + TokenEstimator.estimate(user)
                + reserve + PER_MESSAGE_OVERHEAD * 2;
        int available = b.getContextTokens() - base;
        if (available < 0) {
            throw new BusinessException(ErrorCode.TOKEN_BUDGET_EXCEEDED);
        }
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        LinkedList<LlmGateway.Turn> kept = new LinkedList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {   // 从最新往回
            LlmGateway.Turn t = history.get(i);
            int cost = TokenEstimator.estimate(t.content()) + PER_MESSAGE_OVERHEAD;
            if (used + cost > available) {
                break;   // 再往旧的都装不下
            }
            kept.addFirst(t);
            used += cost;
        }
        return new ArrayList<>(kept);
    }
}
