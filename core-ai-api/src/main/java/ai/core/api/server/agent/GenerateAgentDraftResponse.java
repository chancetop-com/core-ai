package ai.core.api.server.agent;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class GenerateAgentDraftResponse {
    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "system_prompt")
    public String systemPrompt;

    @Property(name = "input_template")
    public String inputTemplate;

    @Property(name = "model")
    public String model;

    @Property(name = "temperature")
    public Double temperature;

    @Property(name = "max_turns")
    public Integer maxTurns;

    @Property(name = "tools")
    public List<ToolRefView> tools;
}
