package ai.core.tool.function;

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
public class Function implements ToolCall {

    public static Builder builder() {
        return new Builder();
    }

    public String name;
    public String description;
    public List<FunctionParameter> parameters;
    public Object object;
    public Method method;
    public ResponseConverter responseConverter;

    @Override
    public String call(String text) {
        var argsMap = JSON.fromJSON(Map.class, text);
        try {
            Object[] args = new Object[this.parameters.size()];
            for (int i = 0; i < this.parameters.size(); i++) {
                Object value = argsMap.get(this.parameters.get(i).name);
                args[i] = ParameterTypeConverters.convert(value, this.parameters.get(i).type);
            }
            return responseConverter.convert(method.invoke(object, args));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(Strings.format("function<{}.{}> failed: params: {}: {}", object.toString(), name, text, e.getMessage()), e);
        }
    }

    public void setMethod(Method method) {
        this.method = method;

        var functionDef = method.getAnnotation(CoreAiMethod.class);
        this.name = functionDef.name();
        this.description = functionDef.description();

        var parameterList = new ArrayList<FunctionParameter>();
        java.lang.reflect.Parameter[] methodParameters = method.getParameters();
        for (java.lang.reflect.Parameter methodParameter : methodParameters) {
            var parameter = getParameter(methodParameter);
            parameterList.add(parameter);
        }
        this.parameters = parameterList;
    }

    @NotNull
    private FunctionParameter getParameter(java.lang.reflect.Parameter methodParameter) {
        var functionParam = methodParameter.getAnnotation(CoreAiParameter.class);
        var parameter = new FunctionParameter();
        parameter.name = functionParam.name();
        parameter.description = functionParam.description();
        parameter.type = methodParameter.getType();
        parameter.required = functionParam.required();
        parameter.enums = List.of(functionParam.enums());
        return parameter;
    }

    @Override
    public String toString() {
        return JSON.toJSON(FunctionDomain.of(this));
    }

    public static class FunctionDomain {
        public static FunctionDomain of(Function function) {
            var domain = new FunctionDomain();
            domain.name = function.name;
            domain.description = function.description;
            domain.parameters = new ArrayList<>(8);
            for (var parameter : function.parameters) {
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
        public static FunctionParameterDomain of(FunctionParameter functionParameter) {
            var domain = new FunctionParameterDomain();
            domain.name = functionParameter.name;
            domain.description = functionParameter.description;
            domain.type = functionParameter.type.getName();
            domain.required = functionParameter.required;
            domain.enums = functionParameter.enums;
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
    public static class Builder {
        private String name;
        private String description;
        private Object object;
        private Method method;
        public List<FunctionParameter> parameters;
        public ResponseConverter responseConverter;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder object(Object object) {
            this.object = object;
            return this;
        }

        public Builder method(Method method) {
            this.method = method;
            return this;
        }

        public Builder parameters(List<FunctionParameter> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Function build() {
            var function = new Function();
            function.name = this.name;
            function.description = this.description;
            function.object = this.object;
            function.method = this.method;
            function.parameters = this.parameters;
            function.responseConverter = this.responseConverter == null ? new DefaultJsonResponseConverter() : this.responseConverter;
            return function;
        }
    }
}
