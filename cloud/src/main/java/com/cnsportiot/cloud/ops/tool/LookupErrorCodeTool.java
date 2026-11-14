package com.cnsportiot.cloud.ops.tool;

import com.cnsportiot.cloud.harness.tool.AgentTool;
import com.cnsportiot.cloud.harness.tool.ScopeKind;
import com.cnsportiot.cloud.harness.tool.ToolContext;
import com.cnsportiot.cloud.harness.tool.ToolSpec;
import com.cnsportiot.contracts.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 运维工具(scope=OPS,只读):解释业务错误码。直读 {@link ErrorCode} 枚举
 * 入参 {@code code} 可选:传数字(如 {@code 40301})按码精确匹配,传名字(如 {@code DATA_SCOPE_DENIED})
 * 按名匹配;不传则列出全部错误码。命中返回 {@link Info},未命中返回 {@link NotFound}。永远最新、O(1)、零污染
 */
@Component
public class LookupErrorCodeTool implements AgentTool {

    private static final ToolSpec SPEC = ToolSpec.readOnly(
            "lookup_error_code",
            "解释业务错误码(直读代码枚举,永远最新)。code 可选:传数字如 40301 按码查,传名字如 DATA_SCOPE_DENIED 按名查,"
                    + "不传列出全部。返回 code/name/httpStatus/message。回答\"xxxxx 是什么意思\"用这个,不要凭记忆。",
            "正在查错误码…",
            """
            {"type":"object","properties":{
              "code":{"type":"string","description":"错误码数字(如 40301)或名字(如 RATE_LIMITED);缺省列出全部"}
            }}""");

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public ScopeKind scope() {
        return ScopeKind.OPS;
    }

    @Override
    public Object execute(Map<String, Object> args, ToolContext ctx) {
        String q = OpsToolArgs.getString(args, "code");
        if (q == null) {
            return new Listing(ErrorCode.values().length,
                    Arrays.stream(ErrorCode.values()).map(Info::of).toList());
        }
        Integer asNumber = tryInt(q);
        for (ErrorCode ec : ErrorCode.values()) {
            boolean hit = asNumber != null ? ec.code() == asNumber : ec.name().equalsIgnoreCase(q);
            if (hit) {
                return Info.of(ec);
            }
        }
        return new NotFound(q, false, "未找到匹配的错误码;可不带参数调用本工具查看全部。");
    }

    private static Integer tryInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单个错误码说明。 */
    public record Info(int code, String name, int httpStatus, String message) {
        static Info of(ErrorCode ec) {
            return new Info(ec.code(), ec.name(), ec.httpStatus().value(), ec.defaultMessage());
        }
    }

    /** 全量列表。 */
    public record Listing(int count, List<Info> codes) {}

    /** 未命中兜底。 */
    public record NotFound(String query, boolean found, String note) {}
}
