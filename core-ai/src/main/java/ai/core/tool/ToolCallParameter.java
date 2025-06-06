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
    String format;
    ToolCallParameterType type;
    Class<?> classType;
    Boolean required;
    List<String> enums;
    Class<?> itemType;
    List<ToolCallParameter> items;
    List<String> itemEnums;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getFormat() {
        return format;
    }

    public Class<?> getClassType() {
        return classType;
    }

    public ToolCallParameterType getType() {
        return type;
    }

    public Boolean isRequired() {
        return required;
    }

    public List<String> getEnums() {
        return enums;
    }

    public Class<?> getItemType() {
        return itemType;
    }

    public List<String> getItemEnums() {
        return itemEnums;
    }

    public List<ToolCallParameter> getItems() {
        return items;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setClassType(Class<?> classType) {
        this.classType = classType;
    }

    public void setType(ToolCallParameterType type) {
        this.type = type;
        this.classType = type.getType();
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public void setEnums(List<String> enums) {
        this.enums = enums;
    }

    public void setItemEnums(List<String> itemEnums) {
        this.itemEnums = itemEnums;
    }

    public void setItemType(Class<?> itemType) {
        this.itemType = itemType;
    }

    public void setItems(List<ToolCallParameter> items) {
        this.items = items;
    }

    public static class Builder {
        private String name;
        private String description;
        private Class<?> classType;
        private ToolCallParameterType type;
        private Boolean required;
        private List<String> enums;
        private String format;
        private List<ToolCallParameter> items;
        private Class<?> itemType;
        private List<String> itemEnums;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder classType(Class<?> type) {
            this.classType = type;
            return this;
        }

        public Builder type(ToolCallParameterType type) {
            this.type = type;
            this.classType = type.getType();
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

        public Builder items(List<ToolCallParameter> items) {
            this.items = items;
            return this;
        }

        public Builder itemEnums(List<String> itemEnums) {
            this.itemEnums = itemEnums;
            return this;
        }

        public Builder itemType(Class<?> itemType) {
            this.itemType = itemType;
            return this;
        }

        public ToolCallParameter build() {
            var parameter = new ToolCallParameter();
            parameter.name = this.name;
            parameter.items = this.items;
            parameter.itemType = this.itemType;
            parameter.type = this.type;
            parameter.format = this.format;
            parameter.itemEnums = this.itemEnums;
            parameter.description = this.description;
            parameter.required = this.required == null ? Boolean.FALSE : this.required;
            parameter.classType = this.classType == null ? String.class : this.classType;
            parameter.enums = this.enums;
            return parameter;
        }
    }
}
