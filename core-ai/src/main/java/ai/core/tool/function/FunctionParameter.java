package ai.core.tool.function;

import java.util.List;

/**
 * @author stephen
 */
public class FunctionParameter {

    public static Builder builder() {
        return new Builder();
    }

    public String name;
    public String description;
    public Class<?> type;
    public Boolean required;
    public List<String> enums;

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

        public FunctionParameter build() {
            var parameter = new FunctionParameter();
            parameter.name = this.name;
            parameter.description = this.description;
            parameter.required = this.required == null ? Boolean.FALSE : this.required;
            parameter.type = this.type == null ? String.class : this.type;
            parameter.enums = this.enums;
            return parameter;
        }
    }
}
