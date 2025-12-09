package ai.core.tool.function;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterUtil;
import ai.core.tool.ToolCallResult;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.tool.function.converter.ResponseConverter;
import ai.core.tool.ToolCall;
import ai.core.tool.function.converter.response.DefaultJsonResponseConverter;
import ai.core.utils.JsonUtil;
import core.framework.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    Boolean dynamicArguments;
    ResponseConverter responseConverter = new DefaultJsonResponseConverter();
    Logger logger = LoggerFactory.getLogger(Function.class);

    private String executeSupport(String text) throws InvocationTargetException, IllegalAccessException {
        if (dynamicArguments != null && dynamicArguments) {
            // args convert by method itself
            var rst = method.invoke(object, List.of(this.getName(), text).toArray());
            return responseConverter.convert(rst);
        } else {
            var argsMap = JsonUtil.fromJson(Map.class, text);
            var args = new Object[this.getParameters().size()];
            for (int i = 0; i < this.getParameters().size(); i++) {
                var name = this.getParameters().get(i).getName();
                var value = argsMap.get(name);
                if (value == null && this.getParameters().get(i).isRequired()) {
                    throw new IllegalAccessException(Strings.format("require arg: {} is null", getName(), name));
                } else if (value == null) {
                    args[i] = null;
                } else {
                    args[i] = JsonUtil.fromJson(method.getParameters()[i].getParameterizedType(), JsonUtil.toJson(value));
                }
            }
            var rst = method.invoke(object, args);
            return responseConverter.convert(rst);
        }
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        logger.info("func text is {}", text);
        long startTime = System.currentTimeMillis();
        try {
            String result = executeSupport(text);
            return ToolCallResult.completed(result).withDuration(System.currentTimeMillis() - startTime);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return ToolCallResult.failed(Strings.format("function<{}.{}> failed: params: {}: {}", object.toString(), getName(), text, e.getMessage()), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    public void setMethod(Method method) {
        this.method = method;

        var functionDef = method.getAnnotation(CoreAiMethod.class);
        this.setName(functionDef.name());
        this.setDescription(functionDef.description());
        this.setNeedAuth(functionDef.needAuth());

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
        parameter.setClassType(methodParameter.getType());

        // Extract generic type for collections/arrays (e.g., List<TodoEntity> -> TodoEntity.class)
        extractGenericItemType(methodParameter.getParameterizedType(), parameter);

        parameter.setRequired(functionParam.required());
        parameter.setEnums(List.of(functionParam.enums()));
        return parameter;
    }

    private void extractGenericItemType(java.lang.reflect.Type parameterizedType, ToolCallParameter parameter) {
        ToolCallParameterUtil.extractGenericItemType(parameterizedType, parameter);
    }

    // keep if dev cannot add annotation in the method
    public static class Builder extends ToolCall.Builder<Builder, Function> {
        private Object object;
        private Method method;
        private ResponseConverter responseConverter;
        private Boolean dynamicArguments;

        public Builder object(Object object) {
            this.object = object;
            return this;
        }

        public Builder method(Method method) {
            this.method = method;
            return this;
        }

        public Builder responseConverter(ResponseConverter responseConverter) {
            this.responseConverter = responseConverter;
            return this;
        }

        public Builder dynamicArguments(Boolean dynamicArguments) {
            this.dynamicArguments = dynamicArguments;
            return this;
        }

        public Function build() {
            var function = new Function();
            build(function);
            function.object = this.object;
            function.method = this.method;
            if (this.responseConverter != null) {
                function.responseConverter = this.responseConverter;
            }
            function.dynamicArguments = this.dynamicArguments;
            return function;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
