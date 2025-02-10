package ai.core.tool;

import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.ArrayList;
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
        return JSON.toJSON(ToolCallDomain.of(this));
    }

    public static class ToolCallDomain {
        public static ToolCallDomain of(ToolCall function) {
            var domain = new ToolCallDomain();
            domain.name = function.getName();
            domain.description = function.getDescription();
            domain.parameters = new ArrayList<>(8);
            for (var parameter : function.getParameters()) {
                domain.parameters.add(ToolCallParameterDomain.of(parameter));
            }
            return domain;
        }

        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "parameters")
        public List<ToolCallParameterDomain> parameters;
    }

    public static class ToolCallParameterDomain {
        public static ToolCallParameterDomain of(ToolCallParameter toolCallParameter) {
            var domain = new ToolCallParameterDomain();
            domain.name = toolCallParameter.getName();
            domain.description = toolCallParameter.getDescription();
            domain.type = toolCallParameter.getType().getName();
            domain.required = toolCallParameter.getRequired();
            domain.enums = toolCallParameter.getEnums();
            return domain;
        }

        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "type")
        public String type;

        @Property(name = "required")
        public boolean required;

        @Property(name = "enums")
        public List<String> enums;
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
