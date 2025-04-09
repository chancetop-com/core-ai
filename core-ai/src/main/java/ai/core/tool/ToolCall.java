package ai.core.tool;

import ai.core.tool.domain.ToolCallDTO;
import core.framework.json.JSON;

import java.util.List;

/**
 * @author stephen
 */
public abstract class ToolCall {
    String name;
    String description;
    List<ToolCallParameter> parameters;

    public abstract String call(String text);

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<ToolCallParameter> getParameters() {
        return parameters;
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

    @Override
    public String toString() {
        return JSON.toJSON(ToolCallDTO.of(this));
    }

    public abstract static class Builder<B extends Builder<B, T>, T extends ToolCall> {
        String name;
        String description;
        public List<ToolCallParameter> parameters;

        // This method needs to be overridden in the subclass Builders
        protected abstract B self();

        public B name(String name) {
            this.name = name;
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
            toolCall.name = name;
            toolCall.description = description;
            toolCall.parameters = parameters;
        }
    }
}
