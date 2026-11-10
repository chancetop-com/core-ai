package ai.core.api.server.agent;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * Resolved agent configuration produced by a preview import: every reference is already mapped to an id
 * in the target environment, and unresolved references were dropped.
 */
public class AgentImportConfigView {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "type")
    public String type;

    @Property(name = "system_prompt")
    public String systemPrompt;

    @Property(name = "model")
    public String model;

    @Property(name = "multi_modal_model")
    public String multiModalModel;

    @Property(name = "prefer_caption_path")
    public Boolean preferCaptionPath;

    @Property(name = "temperature")
    public Double temperature;

    @Property(name = "thinking_effort")
    public String thinkingEffort;

    @Property(name = "max_turns")
    public Integer maxTurns;

    @Property(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Property(name = "tools")
    public List<ToolRefView> tools;

    @Property(name = "skill_ids")
    public List<String> skillIds;

    @Property(name = "subagent_ids")
    public List<String> subAgentIds;

    @Property(name = "input_template")
    public String inputTemplate;

    @Property(name = "variables")
    public Map<String, String> variables;

    @Property(name = "response_schema")
    public String responseSchema;

    @Property(name = "enable_memory")
    public Boolean enableMemory;
}
