package com.cnsportiot.cloud.harness.hook;

import com.cnsportiot.cloud.config.AgentProperties;
import com.cnsportiot.cloud.harness.tool.ToolInvocation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结果后处理钩子
 * 避免把过长的流水一次性回注给 LLM / 下发前端
 * 字段级白名单/剥离算法内部字段(逐帧坐标等)待真实数据接入后在此扩展
 * 并保留 Hook 位点,便于后续无侵入增强
 */
@Component
@Order(100)
public class ResultRedactionHook implements PostToolUseHook {

    private final AgentProperties props;

    public ResultRedactionHook(AgentProperties props) {
        this.props = props;
    }

    @Override
    public Object after(ToolInvocation invocation, Object result) {
        int max = props.getTools().getMaxResultItems();
        if (max > 0 && result instanceof List<?> list && list.size() > max) {
            return List.copyOf(list.subList(0, max));
        }
        return result;
    }
}
