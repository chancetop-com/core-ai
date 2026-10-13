package ai.core.api.server.comparison;

import ai.core.api.server.agent.AgentDatasetConfigView;
import ai.core.api.server.agent.SandboxConfigView;
import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * Editable execution config of one comparison variant. Used both to read the frozen config and as a
 * patch payload when editing variant B before start (null field = keep).
 *
 * @author stephen
 */
public class ComparisonVariantConfigView {
    @Property(name = "system_prompt")
    public String systemPrompt;

    @Property(name = "system_prompt_id")
    public String systemPromptId;

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

    @Property(name = "sandbox_config")
    public SandboxConfigView sandboxConfig;

    @Property(name = "dataset_config")
    public List<AgentDatasetConfigView> datasetConfig;
}
