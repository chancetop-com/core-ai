package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.tool.tools.SubAgentToolCall;
import ai.core.utils.JsonSchemaUtil;
import ai.core.utils.JsonUtil;
import core.framework.util.Strings;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public abstract class ToolCall {
    public static final long DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L;

    String namespace;
    String name;
    String description;
    List<ToolCallParameter> parameters;
    Boolean needAuth;
    Boolean directReturn;
    Boolean llmVisible;
    Boolean discoverable;
    protected Long timeoutMs;

    public ToolCallResult execute(String arguments, ExecutionContext context) {
        return execute(arguments);
    }

    public abstract ToolCallResult execute(String arguments);

    public ToolCallResult poll(String taskId) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support polling");
    }

    public ToolCallResult submitInput(String taskId, String input) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support user input");
    }

    public ToolCallResult cancel(String taskId) {
        throw new UnsupportedOperationException("Tool '" + name + "' does not support cancellation");
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getDescription() {
        return description;
    }

    public List<ToolCallParameter> getParameters() {
        return parameters;
    }

    public Boolean isNeedAuth() {
        return needAuth;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setParameters(List<ToolCallParameter> parameters) {
        this.parameters = parameters;
    }

    public void setNeedAuth(Boolean needAuth) {
        this.needAuth = needAuth;
    }


    public Boolean isDirectReturn() {
        return directReturn != null && directReturn;
    }

    public void setDirectReturn(Boolean directReturn) {
        this.directReturn = directReturn;
    }

    public Boolean isLlmVisible() {
        return llmVisible == null || llmVisible;
    }

    public void setLlmVisible(Boolean llmVisible) {
        this.llmVisible = llmVisible;
    }

    public boolean isDiscoverable() {
        return discoverable != null && discoverable;
    }

    public void setDiscoverable(Boolean discoverable) {
        this.discoverable = discoverable;
    }

    public long getTimeoutMs() {
        return timeoutMs != null ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isSubAgent() {
        return false;
    }

    @Override
    public String toString() {
        return JsonUtil.toJson(toTool());
    }

    public Tool toTool() {
        var tool = new Tool();
        tool.type = ToolType.FUNCTION;
        var func = new Function();
        func.name = name;
        // todo: better name truncation strategy
        if (func.name.length() > 64) {
            func.name = func.name.substring(func.name.length() - 64);
            if (func.name.contains("_")) {
                func.name = func.name.substring(func.name.indexOf('_') + 1);
            }
        }
        func.description = getDescription();
        func.parameters = toJsonSchema();
        tool.function = func;
        return tool;
    }

    public JsonSchema toJsonSchema() {
        return JsonSchemaUtil.toJsonSchema(parameters);
    }

    public List<String> findMissingRequiredParams(String arguments) {
        if (parameters == null || parameters.isEmpty()) return List.of();
        var required = parameters.stream().filter(p -> Boolean.TRUE.equals(p.isRequired())).toList();
        if (required.isEmpty()) return List.of();
        try {
            var args = JsonUtil.fromJson(Map.class, arguments);
            return required.stream()
                    .filter(p -> !args.containsKey(p.getName()) || args.get(p.getName()) == null)
                    .map(ToolCallParameter::getName)
                    .toList();
        } catch (Exception e) {
            return List.of("(arguments is not valid JSON)");
        }
    }

    public abstract static class Builder<B extends Builder<B, T>, T extends ToolCall> {
        String namespace;
        String name;
        String description;
        List<ToolCallParameter> parameters;
        Boolean needAuth;
        Boolean continueAfterSlash;
        Boolean directReturn;
        Boolean llmVisible;
        Boolean discoverable;
        Long timeoutMs;

        protected abstract B self();

        public B name(String name) {
            this.name = name;
            return self();
        }

        public B namespace(String namespace) {
            this.namespace = namespace;
            return self();
        }

        public B description(String description) {
            this.description = description;
            return self();
        }

        public B parameters(List<ToolCallParameter> parameters) {
            this.parameters = parameters;
            return self();
        }

        public B needAuth(Boolean needAuth) {
            this.needAuth = needAuth;
            return self();
        }

        public B continueAfterSlash(Boolean continueAfterSlash) {
            this.continueAfterSlash = continueAfterSlash;
            return self();
        }

        public B directReturn(Boolean directReturn) {
            this.directReturn = directReturn;
            return self();
        }

        public B llmVisible(Boolean llmVisible) {
            this.llmVisible = llmVisible;
            return self();
        }

        public B discoverable(Boolean discoverable) {
            this.discoverable = discoverable;
            return self();
        }

        public B timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return self();
        }

        public void build(T toolCall) {
            if (name == null) {
                throw new RuntimeException("name is required");
            }
            if (description == null) {
                throw new RuntimeException("description is required");
            }
            if (parameters == null) {
                parameters = List.of();
            }
            toolCall.namespace = namespace;
            toolCall.name = name;
            if (toolCall instanceof SubAgentToolCall) {
                // for sub-agent tool, we put the description in the tool call description, so that it can be used as the sub-agent execution description.
                toolCall.description = Strings.format("""
                        This is a subagent tool.
                        When the agent calls this tool, it will trigger a subagent execution with the following description and parameters.
                        Here is the description of the subagent to be executed:
                        {}
                        """, description);
            } else {
                toolCall.description = description;
            }
            toolCall.parameters = parameters;
            toolCall.needAuth = needAuth != null && needAuth;
            toolCall.directReturn = directReturn != null && directReturn;
            toolCall.llmVisible = llmVisible == null || llmVisible;
            toolCall.discoverable = discoverable != null && discoverable;
            toolCall.timeoutMs = timeoutMs;
        }
    }
}
