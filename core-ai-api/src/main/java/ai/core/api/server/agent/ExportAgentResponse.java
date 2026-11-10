package ai.core.api.server.agent;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Portable export envelope for an agent definition. Environment-bound resources are carried as names
 * so the same file can be imported into another deployment; the import resolves them against the
 * target environment and reports what it could not find.
 */
public class ExportAgentResponse {
    @Property(name = "format")
    public String format;

    @Property(name = "exported_at")
    public ZonedDateTime exportedAt;

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

    @Property(name = "skills")
    public List<AgentSkillRefView> skills;

    @Property(name = "subagents")
    public List<AgentSubAgentRefView> subagents;

    /**
     * Legacy files exported before names were carried only have ids. Kept so those files still import;
     * unresolved ids are reported instead of failing the whole import.
     */
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
