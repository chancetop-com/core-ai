package ai.core.tool.function;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import ai.core.tool.function.converter.ResponseConverter;
import ai.core.tool.function.converter.response.DefaultJsonResponseConverter;
import ai.core.utils.JsonUtil;
import core.framework.log.Markers;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

    private String executeSupport(String text, ExecutionContext context) throws InvocationTargetException, IllegalAccessException {
        if (dynamicArguments != null && dynamicArguments) {
            // args convert by method itself
            var rst = method.invoke(object, List.of(this.getName(), text).toArray());
            return responseConverter.convert(rst);
        }
        var argsMap = JsonUtil.fromJson(Map.class, text);
        var methodParams = method.getParameters();
        var args = new Object[methodParams.length];

        int jsonParamIndex = 0;
        for (int i = 0; i < methodParams.length; i++) {
            // auto-inject ExecutionContext type parameter
            if (methodParams[i].getType() == ExecutionContext.class) {
                args[i] = context;
            } else {
                var toolParam = this.getParameters().get(jsonParamIndex);
                var value = argsMap.get(toolParam.getName());
                if (value == null && toolParam.isRequired()) {
                    throw new IllegalAccessException(Strings.format("require arg: {} is null", getName(), toolParam.getName()));
                } else if (value == null) {
                    args[i] = null;
                } else {
                    args[i] = JsonUtil.fromJson(methodParams[i].getParameterizedType(), JsonUtil.toJson(value));
                }
                jsonParamIndex++;
            }
        }
        var rst = method.invoke(object, args);
        return responseConverter.convert(rst);
    }

    @Override
    public ToolCallResult execute(String text, ExecutionContext context) {
        logger.info("func text is {}", text);
        long startTime = System.currentTimeMillis();
        try {
            var result = executeSupport(text, context);
            return ToolCallResult.completed(result).withDuration(System.currentTimeMillis() - startTime).withDirectReturn(isDirectReturn());
        } catch (IllegalAccessException | InvocationTargetException e) {
            logger.error(Markers.errorCode("FUNCTION_EXECUTE_FAILED"), "function<{}.{}> execute failed, params: {}", object.toString(), getName(), text, e);
            return ToolCallResult.failed(Strings.format("function<{}.{}> failed: params: {}: {}", object.toString(), getName(), text, e.getMessage()), e)
                    .withDuration(System.currentTimeMillis() - startTime).withDirectReturn(isDirectReturn());
        }
    }

    @Override
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    // Builder for manual Function creation (when annotations cannot be used)
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
