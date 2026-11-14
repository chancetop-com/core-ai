package ai.core.server.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityRuleKind;
import ai.core.server.quality.domain.QualityRuleLifecycle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The six required detections, split into eight rules. These are seeded into every environment; an admin
 * may change parameters and enforcement but cannot delete the logical identity.
 *
 * <p>B1/B2/B3 are seeded SHADOW as the staged rollout requires: they add decision-model cost and need
 * calibration on real agents before they may block. C1 is BLOCK because it is a requirement, not a
 * heuristic: a published agent with no function summary is exactly what the user asked to prevent.
 *
 * @author core-ai
 */
public final class BuiltInQualityRules {
    public static final List<Definition> DEFINITIONS = List.of(
        new Definition("CFG.REFERENCE_AVAILABLE", "引用资源有效性", "CONFIG", QualityRuleKind.CODE,
            ReferenceAvailableRule.KEY,
            "所有配置引用必须存在、可用且类型正确。",
            "{{resource_type}} 引用 {{resource_id}} 不存在或不可用。",
            "移除不可用的引用，或重新选择有效配置。",
            Map.of(), QualityEnforcement.BLOCK),
        new Definition("CFG.MCP_SERVER_AVAILABLE", "MCP server 有效性", "CONFIG", QualityRuleKind.CODE,
            McpServerAvailableRule.KEY,
            "MCP server 引用必须存在、启用、类型正确且配置可解析。",
            "MCP server 引用不可用（{{resource_id}}）。",
            "选择存在的 MCP server 并确保已启用、配置可解析，或移除该引用。",
            Map.of(), QualityEnforcement.BLOCK),
        new Definition("CFG.MCP_TOOL_AVAILABLE", "MCP tool 存在性", "CONFIG", QualityRuleKind.CODE,
            McpToolAvailableRule.KEY,
            "固定 MCP tool 的存在性需要可信 tool 列表缓存；未缓存时返回未验证。",
            "MCP tool 列表不可用，无法验证 {{resource_id}} 是否存在。",
            "确认该 MCP tool 仍存在；服务端尚未缓存 tool 列表，因此本条在首版仅审计。",
            Map.of(), QualityEnforcement.SHADOW),
        new Definition("CFG.RESOURCE_COUNT_LIMIT", "配置数量上限", "CONFIG", QualityRuleKind.CODE,
            ResourceCountLimitRule.KEY,
            "tool / skill / sub-agent 等直接引用数量不得超过配置上限。",
            "{{category}} 为 {{actual_count}} 个，上限 {{max_count}} 个。",
            "至少减少 {{reduction}} 个 {{category}}；保留必需项，或联系规则管理员调整上限。",
            countDefaults(), QualityEnforcement.BLOCK),
        new Definition("PROMPT.TIME_CONTEXT_REQUIRED", "时间指令必须有有效时间上下文", "PROMPT",
            QualityRuleKind.HYBRID, TimeContextRule.KEY,
            "适用的时间指令必须配合真实渲染的 system.time 注入。",
            "提示词含适用时间指令，但未发现有效 system.time 注入。",
            "在参与运行时渲染的 system prompt 中加入：当前时间：{{system.time}}，或删除不需要的时间指令。",
            Map.of("temporalScope", "CURRENT_TIME_DEPENDENT"), QualityEnforcement.SHADOW),
        new Definition("PROMPT.BUILTIN_CAPABILITY_REQUIRED", "操作意图必须有 builtin 能力", "PROMPT",
            QualityRuleKind.HYBRID, BuiltinCapabilityRule.KEY,
            "提示词要求文件、网络、代码、图片或视频操作时，必须配置对应 builtin 能力。",
            "提示词要求执行 {{capability}}，但 Agent 缺少对应 builtin 能力。",
            "启用对应 builtin 能力，或改为不需要该操作的指令。",
            Map.of("providerPolicy", "BUILTIN_REQUIRED"), QualityEnforcement.SHADOW),
        new Definition("PROMPT.RESOURCE_BINDING_REQUIRED", "指令所需资源与配置一致", "PROMPT",
            QualityRuleKind.HYBRID, ResourceBindingRule.KEY,
            "提示词中实际要求使用的命名资源必须有对应配置。",
            "提示词要求使用 {{resource_type}} {{resource_id}}，但配置中没有对应绑定。",
            "选择并绑定有效资源，或删除这项调用指令。",
            Map.of("catalogLimit", "2000"), QualityEnforcement.SHADOW),
        new Definition("DESC.FUNCTION_SUMMARY_REQUIRED", "description 必须包含功能简介", "DESCRIPTION",
            QualityRuleKind.HYBRID, FunctionSummaryRule.KEY,
            "description 必须让读者明确知道 Agent 的至少一项实际功能。",
            "description 没有明确介绍 Agent 的功能。",
            "补充“做什么 + 服务什么对象/产出什么”，例如“分析销售数据并生成周报”。",
            Map.of(), QualityEnforcement.BLOCK)
    );

    private static Map<String, String> countDefaults() {
        var params = new LinkedHashMap<String, String>();
        params.put("tool_refs_total", "50");
        params.put("skills", "50");
        params.put("subagents", "50");
        return params;
    }

    public static Definition require(String key) {
        return DEFINITIONS.stream().filter(definition -> definition.key.equals(key)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown built-in quality rule: " + key));
    }

    public static String ruleId(String key) {
        return "qrule:" + key;
    }

    public static QualityRuleLifecycle lifecycleOf(QualityEnforcement enforcement) {
        return enforcement == QualityEnforcement.SHADOW
            ? QualityRuleLifecycle.SHADOW : QualityRuleLifecycle.ENFORCING;
    }

    private BuiltInQualityRules() {
    }

    public record Definition(String key, String name, String category, QualityRuleKind kind,
                             String evaluatorKey, String description, String messageTemplate,
                             String remediationTemplate, Map<String, String> parameters,
                             QualityEnforcement enforcement) {
    }
}
