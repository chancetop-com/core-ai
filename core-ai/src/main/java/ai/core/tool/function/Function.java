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

/**
 * @author stephen
 */
public class Function extends ToolCall {
    static final int MAX_ERROR_PARAMS_LENGTH = 500;

    public static Builder builder() {
        return new Builder();
    }

    Object object;
    Method method;
    Boolean dynamicArguments;
    ResponseConverter responseConverter = new DefaultJsonResponseConverter();
    Logger logger = LoggerFactory.getLogger(Function.class);

    private String executeSupport(String text, ExecutionContext context) throws InvocationTargetException, IllegalAccessException {
        var argsMap = parseArguments(text);
        if (context != null && context.getCustomVariables() != null) {
            context.getCustomVariables().forEach((key, customValue) -> {
                // skip internal runtime objects (e.g. the URL resolver); they are not serializable tool arguments
                if (key.startsWith(ExecutionContext.INTERNAL_VARIABLE_PREFIX)) {
                    return;
                }
                // skip null/blank values so empty agent variables do not overwrite arguments provided by the model
                if (customValue == null || customValue instanceof String strValue && strValue.isBlank()) {
                    return;
                }
                argsMap.put(key, customValue);
            });
        }
        if (dynamicArguments != null && dynamicArguments) {
            // args convert by method itself; a trailing ExecutionContext parameter is auto-injected like
            // the non-dynamic path does, so dispatchers can scope their work to the calling user
            var json = JsonUtil.toJson(argsMap);
            var parameterTypes = method.getParameterTypes();
            var rst = parameterTypes.length == 3 && parameterTypes[2] == ExecutionContext.class
                    ? method.invoke(object, this.getName(), json, context)
                    : method.invoke(object, this.getName(), json);
            return responseConverter.convert(rst);
        }
        var methodParams = method.getParameters();
        var args = new Object[methodParams.length];

        int jsonParamIndex = 0;
        for (int i = 0; i < methodParams.length; i++) {
            // auto-inject ExecutionContext type parameter
            if (methodParams[i].getType() == ExecutionContext.class) {
                args[i] = context;
            } else {
                var toolParam = this.getParameters().get(jsonParamIndex);
                Object value;
                if (toolParam.isFlatten()) {
                    value = argsMap;
                } else {
                    value = argsMap.get(toolParam.getName());
                }
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
            // InvocationTargetException.getMessage() is null; surface the underlying cause so the trace shows a real error
            var cause = e instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : e;
            logger.error(Markers.errorCode("FUNCTION_EXECUTE_FAILED"), "function<{}.{}> execute failed, params: {}", object.toString(), getName(), text, e);
            // truncate the params echo: the failed result lives in conversation history for the rest of the
            // run — a large batch payload echoed verbatim (e.g. a 15KB shotlist) bloats every later turn
            return ToolCallResult.failed(Strings.format("function<{}.{}> failed: params: {}: {}", object.toString(), getName(), truncate(text), cause.toString()), e)
                    .withDuration(System.currentTimeMillis() - startTime).withDirectReturn(isDirectReturn());
        }
    }

    @Override
    public ToolCallResult execute(String text) {
        return execute(text, null);
    }

    private String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_PARAMS_LENGTH) return text;
        return text.substring(0, MAX_ERROR_PARAMS_LENGTH) + "... (" + (text.length() - MAX_ERROR_PARAMS_LENGTH) + " chars truncated)";
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
