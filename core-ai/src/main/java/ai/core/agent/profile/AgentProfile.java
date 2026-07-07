package ai.core.agent.profile;

import java.util.List;

/**
 * Pure data object representing a user-defined agent configuration.
 * No filesystem or persistence knowledge — populated by AgentProfileProvider implementations.
 *
 * @author lim chen
 */
public class AgentProfile {
    private String name;
    private String description;
    private String systemPrompt;
    private String model;
    private Double temperature;
    private Integer maxTurnNumber;
    private List<String> tools;
    private List<String> disallowedTools;
    private String path;
    private String source;
    private int priority;

    public String name() {
        return name;
    }

    public AgentProfile name(String name) {
        this.name = name;
        return this;
    }

    public String description() {
        return description;
    }

    public AgentProfile description(String description) {
        this.description = description;
        return this;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public AgentProfile systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public String model() {
        return model;
    }

    public AgentProfile model(String model) {
        this.model = model;
        return this;
    }

    public Double temperature() {
        return temperature;
    }

    public AgentProfile temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public Integer maxTurnNumber() {
        return maxTurnNumber;
    }

    public AgentProfile maxTurnNumber(Integer maxTurnNumber) {
        this.maxTurnNumber = maxTurnNumber;
        return this;
    }

    public List<String> tools() {
        return tools;
    }

    public AgentProfile tools(List<String> tools) {
        this.tools = tools;
        return this;
    }

    public List<String> disallowedTools() {
        return disallowedTools;
    }

    public AgentProfile disallowedTools(List<String> disallowedTools) {
        this.disallowedTools = disallowedTools;
        return this;
    }

    public String path() {
        return path;
    }

    public AgentProfile path(String path) {
        this.path = path;
        return this;
    }

    public String source() {
        return source;
    }

    public AgentProfile source(String source) {
        this.source = source;
        return this;
    }

    public int priority() {
        return priority;
    }

    public AgentProfile priority(int priority) {
        this.priority = priority;
        return this;
    }
}
