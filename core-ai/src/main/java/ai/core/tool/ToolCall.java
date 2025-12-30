package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.utils.JsonSchemaUtil;
import ai.core.utils.JsonUtil;

import java.util.List;

/**
 * @author stephen
 */
public abstract class ToolCall {
    String namespace;
    String name;
    String description;
    List<ToolCallParameter> parameters;
    Boolean needAuth;
    Boolean directReturn;
    Boolean llmVisible;

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
        func.description = description;
        func.parameters = toJsonSchema();
        tool.function = func;
        return tool;
    }

    public JsonSchema toJsonSchema() {
        return JsonSchemaUtil.toJsonSchema(parameters);
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

        public B llmVisible(Boolean llmVisible) {
            this.llmVisible = llmVisible;
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
            toolCall.description = description;
            toolCall.parameters = parameters;
            toolCall.needAuth = needAuth != null && needAuth;
            toolCall.directReturn = directReturn != null && directReturn;
            toolCall.llmVisible = llmVisible == null || llmVisible;
        }
    }
}
