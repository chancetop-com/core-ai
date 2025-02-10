package ai.core.tool;

import java.util.List;

/**
 * @author stephen
 */
public class ToolCallParameter {

    public static Builder builder() {
        return new Builder();
    }

    String name;
    String description;
    Class<?> type;
    Boolean required;
    List<String> enums;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Class<?> getType() {
        return type;
    }

    public Boolean getRequired() {
        return required;
    }

    public List<String> getEnums() {
        return enums;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setType(Class<?> type) {
        this.type = type;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public void setEnums(List<String> enums) {
        this.enums = enums;
    }

    public static class Builder {
        private String name;
        private String description;
        private Class<?> type;
        private Boolean required;
        private List<String> enums;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder type(Class<?> type) {
            this.type = type;
            return this;
        }

        public Builder required(Boolean required) {
            this.required = required;
            return this;
        }

        public Builder enums(List<String> enums) {
            this.enums = enums;
            return this;
        }

        public ToolCallParameter build() {
            var parameter = new ToolCallParameter();
            parameter.name = this.name;
            parameter.description = this.description;
            parameter.required = this.required == null ? Boolean.FALSE : this.required;
            parameter.type = this.type == null ? String.class : this.type;
            parameter.enums = this.enums;
            return parameter;
        }
    }
}
