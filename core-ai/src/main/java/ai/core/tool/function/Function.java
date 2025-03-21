package ai.core.tool.function;

import ai.core.tool.ToolCallParameter;
import ai.core.tool.function.annotation.CoreAiMethod;
import ai.core.tool.function.annotation.CoreAiParameter;
import ai.core.tool.function.converter.ParameterTypeConverters;
import ai.core.tool.function.converter.ResponseConverter;
import ai.core.tool.ToolCall;
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
            var args = new Object[this.getParameters().size()];
            for (int i = 0; i < this.getParameters().size(); i++) {
                var value = argsMap.get(this.getParameters().get(i).getName());
                args[i] = ParameterTypeConverters.convert(value, this.getParameters().get(i).getType());
            }
            var rst = method.invoke(object, args);
            return responseConverter != null ? responseConverter.convert(rst) : (String) rst;
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
        var methodParameters = method.getParameters();
        for (var methodParameter : methodParameters) {
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
            function.responseConverter = this.responseConverter;
            return function;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
