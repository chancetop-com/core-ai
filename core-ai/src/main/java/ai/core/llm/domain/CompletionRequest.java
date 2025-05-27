package ai.core.llm.domain;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CompletionRequest {
    public static CompletionRequest of(List<Message> messages, List<Tool> tools, Double temperature, String model, String name) {
        var request = new CompletionRequest();
        request.model = model;
        request.messages = messages;
        request.temperature = temperature;
        request.name = name;
        request.tools = tools;
        return request;
    }

    @Property(name = "model")
    public String model;
    @Property(name = "messages")
    public List<Message> messages;
    @Property(name = "temperature")
    public Double temperature;
    @Property(name = "tools")
    public List<Tool> tools;
    @Property(name = "tool_choice")
    public String toolChoice;

    private String name;

    public String getName() {
        return name;
    }
}
