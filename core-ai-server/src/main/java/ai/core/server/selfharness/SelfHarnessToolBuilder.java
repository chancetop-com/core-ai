package ai.core.server.selfharness;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.function.Function;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;
import core.framework.api.web.service.QueryParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code @Property} / {@code @QueryParam} annotations from request
 * DTO classes and auto-generates {@link ToolCall} instances backed by a
 * {@link SelfHarnessApiCaller} dispatcher.
 * <p>
 * This replaces per-tool manual parameter wiring with annotation-driven
 * schema derivation, so tool definitions stay in sync with the request
 * DTOs automatically.
 *
 * @author stephen
 */
public class SelfHarnessToolBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(SelfHarnessToolBuilder.class);

    private final SelfHarnessApiCaller caller;
    private final Method callApiMethod;

    public SelfHarnessToolBuilder(SelfHarnessApiCaller caller) {
        this.caller = caller;
        this.callApiMethod = findCallApiMethod();
    }

    /**
     * Builds a tool whose parameters are derived from the request DTO's
     * {@code @Property} / {@code @QueryParam} annotations.
     */
    public ToolCall build(String name, String description, Class<?> requestType, boolean hasPathParamId) {
        var params = buildParameters(requestType, hasPathParamId);
        return Function.builder()
                .namespace("self-harness")
                .sourceType("self-harness")
                .name(name)
                .description(description)
                .object(caller)
                .method(callApiMethod)
                .dynamicArguments(true)
                .parameters(params)
                .build();
    }

    /**
     * Builds a tool with only a path parameter (e.g. get / publish / delete).
     */
    public ToolCall buildWithPathParamOnly(String name, String description, String pathParamName, String pathParamDesc) {
        var params = List.of(ToolCallParameter.builder()
                .name(pathParamName)
                .description(pathParamDesc)
                .type(ToolCallParameterType.STRING)
                .required(true)
                .build());
        return Function.builder()
                .namespace("self-harness")
                .sourceType("self-harness")
                .name(name)
                .description(description)
                .object(caller)
                .method(callApiMethod)
                .dynamicArguments(true)
                .parameters(params)
                .build();
    }

    /**
     * Builds a tool with manually specified parameters, for operations that
     * don't have a @Property-annotated request DTO (e.g. trace queries).
     */
    public ToolCall buildCustom(String name, String description, List<ToolCallParameter> params) {
        return Function.builder()
                .namespace("self-harness")
                .sourceType("self-harness")
                .name(name)
                .description(description)
                .object(caller)
                .method(callApiMethod)
                .dynamicArguments(true)
                .parameters(params)
                .build();
    }

    // ---- parameter derivation from annotations ----

    private List<ToolCallParameter> buildParameters(Class<?> requestType, boolean hasPathParamId) {
        var params = new ArrayList<ToolCallParameter>();
        if (hasPathParamId) {
            params.add(ToolCallParameter.builder()
                    .name("id")
                    .description("Agent ID")
                    .type(ToolCallParameterType.STRING)
                    .required(true)
                    .build());
        }
        for (var field : requestType.getDeclaredFields()) {
            var param = buildParameterFromField(field);
            if (param != null) {
                params.add(param);
            }
        }
        return params;
    }

    private ToolCallParameter buildParameterFromField(Field field) {
        var property = field.getAnnotation(Property.class);
        var queryParam = field.getAnnotation(QueryParam.class);
        if (property == null && queryParam == null) return null;

        var notNull = field.getAnnotation(NotNull.class);
        String name;
        if (property != null) {
            name = property.name();
        } else {
            name = queryParam.name();
        }

        var builder = ToolCallParameter.builder()
                .name(name)
                .description(name)  // annotation-driven, description comes from the field name
                .required(notNull != null);

        setTypeFromFieldType(builder, field);
        return builder.build();
    }

    private void setTypeFromFieldType(ToolCallParameter.Builder builder, Field field) {
        var type = field.getType();
        if (type == String.class) {
            builder.type(ToolCallParameterType.STRING);
        } else if (type == Boolean.class || type == boolean.class) {
            builder.type(ToolCallParameterType.BOOLEAN);
        } else if (type == Integer.class || type == int.class) {
            builder.type(ToolCallParameterType.INTEGER);
        } else if (type == Long.class || type == long.class) {
            builder.type(ToolCallParameterType.INTEGER);
        } else if (type == Double.class || type == double.class || type == Float.class || type == float.class) {
            builder.type(ToolCallParameterType.DOUBLE);
        } else if (List.class.isAssignableFrom(type)) {
            var genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length > 0) {
                var itemClass = resolveItemClass(pt.getActualTypeArguments()[0]);
                builder.type(ToolCallParameterType.LIST).itemType(itemClass != null ? itemClass : String.class);
            } else {
                builder.type(ToolCallParameterType.LIST).itemType(String.class);
            }
        } else if (Map.class.isAssignableFrom(type)) {
            builder.type(ToolCallParameterType.MAP);
        } else {
            // nested object or unknown → treat as Map
            builder.type(ToolCallParameterType.MAP);
        }
    }

    private Class<?> resolveItemClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
        return null;
    }

    private static Method findCallApiMethod() {
        return Arrays.stream(SelfHarnessApiCaller.class.getMethods())
                .filter(m -> m.getName().equals("callApi"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("callApi method not found"));
    }
}
