package ai.core.tool.function;

import ai.core.tool.ToolCallParameter;
import ai.core.tool.function.annotation.CoreAiMethod;
import ai.core.tool.function.annotation.CoreAiParameter;
import ai.core.tool.function.converter.response.DefaultJsonResponseConverter;
import ai.core.tool.function.converter.ParameterTypeConverters;
import ai.core.tool.function.converter.ResponseConverter;
import ai.core.tool.ToolCall;
import core.framework.api.json.Property;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class Function extends ToolCall {

    public static Builder builder() {
        return new Builder();
    }

    Object object;
    Method method;
    ResponseConverter responseConverter;

    @Override
    public String call(String text) {
        var argsMap = JSON.fromJSON(Map.class, text);
        try {
            Object[] args = new Object[this.getParameters().size()];
            for (int i = 0; i < this.getParameters().size(); i++) {
                Object value = argsMap.get(this.getParameters().get(i).getName());
                args[i] = ParameterTypeConverters.convert(value, this.getParameters().get(i).getType());
            }
            return responseConverter.convert(method.invoke(object, args));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(Strings.format("function<{}.{}> failed: params: {}: {}", object.toString(), getName(), text, e.getMessage()), e);
        }
    }

    public void setMethod(Method method) {
        this.method = method;

        var functionDef = method.getAnnotation(CoreAiMethod.class);
        this.setName(functionDef.name());
        this.setDescription(functionDef.description());

        var parameterList = new ArrayList<ToolCallParameter>();
        java.lang.reflect.Parameter[] methodParameters = method.getParameters();
        for (java.lang.reflect.Parameter methodParameter : methodParameters) {
            var parameter = getParameter(methodParameter);
            parameterList.add(parameter);
        }
        this.setParameters(parameterList);
    }

    @NotNull
    private ToolCallParameter getParameter(java.lang.reflect.Parameter methodParameter) {
        var functionParam = methodParameter.getAnnotation(CoreAiParameter.class);
        var parameter = new ToolCallParameter();
        parameter.setName(functionParam.name());
        parameter.setDescription(functionParam.description());
        parameter.setType(methodParameter.getType());
        parameter.setRequired(functionParam.required());
        parameter.setEnums(List.of(functionParam.enums()));
        return parameter;
    }

    @Override
    public String toString() {
        return JSON.toJSON(FunctionDomain.of(this));
    }

    public static class FunctionDomain {
        public static FunctionDomain of(Function function) {
            var domain = new FunctionDomain();
            domain.name = function.getName();
            domain.description = function.getDescription();
            domain.parameters = new ArrayList<>(8);
            for (var parameter : function.getParameters()) {
                domain.parameters.add(FunctionParameterDomain.of(parameter));
            }
            return domain;
        }

        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;

        @Property(name = "parameters")
        public List<FunctionParameterDomain> parameters;
    }

    public static class FunctionParameterDomain {
        public static FunctionParameterDomain of(ToolCallParameter toolCallParameter) {
            var domain = new FunctionParameterDomain();
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

    // keep if dev cannot add annotation in the method
    public static class Builder extends ToolCall.Builder<Builder, Function> {
        private Object object;
        private Method method;
        public ResponseConverter responseConverter;

        public Builder object(Object object) {
            this.object = object;
            return this;
        }

        public Builder method(Method method) {
            this.method = method;
            return this;
        }

        public Function build() {
            var function = new Function();
            build(function);
            function.object = this.object;
            function.method = this.method;
            function.responseConverter = this.responseConverter == null ? new DefaultJsonResponseConverter() : this.responseConverter;
            return function;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
