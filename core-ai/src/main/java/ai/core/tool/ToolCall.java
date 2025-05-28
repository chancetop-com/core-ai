package ai.core.tool;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.tool.domain.ToolCallDTO;
import ai.core.utils.JsonSchemaUtil;
import core.framework.json.JSON;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public abstract class ToolCall {
    String name;
    String description;
    List<ToolCallParameter> parameters;
    Boolean needAuth;

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

    public Boolean getNeedAuth() {
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

    @Override
    public String toString() {
        return JSON.toJSON(ToolCallDTO.of(this));
    }

    public Tool toTool() {
        var tool = new Tool();
        tool.type = ToolType.FUNCTION;
        var func = new Function();
        func.name = name;
        func.description = description;
        func.parameters = toJsonSchema();
        tool.function = func;
        return tool;
    }

    public JsonSchema toJsonSchema() {
        return toJsonSchema(parameters, JsonSchema.PropertyType.OBJECT, null);
    }

    private JsonSchema toJsonSchema(List<ToolCallParameter> parameters, JsonSchema.PropertyType propertyType, ToolCallParameter parent) {
        var schema = new JsonSchema();
        schema.type = propertyType;
        if (parent != null) {
            schema.enums = parent.getItemEnums();
        }
        schema.required = parameters.stream().filter(
                v -> v.getRequired() != null
                        && v.getRequired()
                        && v.getName() != null).map(ToolCallParameter::getName).toList();
        schema.properties = parameters.stream().filter(v -> v.getName() != null).collect(Collectors.toMap(ToolCallParameter::getName, this::toSchemaProperty));
        return schema;
    }

    private JsonSchema toSchemaProperty(ToolCallParameter p) {
        var property = new JsonSchema();
        property.description = p.getDescription();
        property.type = JsonSchemaUtil.buildJsonSchemaType(p.getClassType());
        property.enums = p.getEnums();
        property.format = p.getFormat();
        if (property.type == JsonSchema.PropertyType.ARRAY) {
            if (p.getItems() != null && !p.getItems().isEmpty()) {
                property.items = toJsonSchema(p.getItems(), toType(p.getItemType()), p);
            } else {
                property.items = new JsonSchema();
                property.items.type = toType(p.getItemType());
                property.enums = p.getItemEnums();
            }
        }
        return property;
    }

    private JsonSchema.PropertyType toType(Class<?> c) {
        var n = c.getSimpleName().substring(c.getSimpleName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        if ("object".equalsIgnoreCase(n)) {
            return JsonSchema.PropertyType.OBJECT;
        }
        return JsonSchemaUtil.buildJsonSchemaType(c);
    }

    public abstract static class Builder<B extends Builder<B, T>, T extends ToolCall> {
        String name;
        String description;
        List<ToolCallParameter> parameters;
        Boolean needAuth;

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

        public B needAuth(Boolean needAuth) {
            this.needAuth = needAuth;
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
            toolCall.needAuth = needAuth != null && needAuth;
        }
    }
}
