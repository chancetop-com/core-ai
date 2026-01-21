package ai.core.tool.function;

import ai.core.agent.ExecutionContext;
import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterUtil;

import java.io.Serial;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * @author stephen
 */
public class Functions extends ArrayList<Function> {
    @Serial
    private static final long serialVersionUID = -2523451400454678077L;

    public static List<Function> from(Object object) {
        return from(object.getClass(), object, getAllMethods(object.getClass(), method -> method.getAnnotation(CoreAiMethod.class) != null).stream().map(Method::getName).toArray(String[]::new));
    }

    public static List<Function> from(Object object, String... methodNames) {
        return from(object.getClass(), object, methodNames);
    }

    private static List<Function> from(Class<?> clazz, Object object, String... methodNames) {
        var methodList = getAllMethods(clazz, method -> {
            if (object == null && !Modifier.isStatic(method.getModifiers())) {
                return false;
            }
            if (method.getAnnotation(CoreAiMethod.class) == null) {
                return false;
            }
            if (methodNames.length > 0) {
                return Arrays.stream(methodNames).anyMatch(v -> v.equalsIgnoreCase(method.getName()));
            }
            return true;
        });

        var functions = new Functions();
        for (var method : methodList) {
            var function = buildFunction(method, object);
            functions.add(function);
        }

        return functions;
    }

    private static Function buildFunction(Method method, Object object) {
        var functionDef = method.getAnnotation(CoreAiMethod.class);
        var builder = Function.builder()
                .method(method)
                .name(functionDef.name())
                .description(functionDef.description())
                .needAuth(functionDef.needAuth())
                .directReturn(functionDef.directReturn())
                .llmVisible(functionDef.llmVisible())
                .parameters(buildParameters(method));

        // Set timeout if specified
        if (functionDef.timeoutMs() > 0) {
            builder.timeoutMs(functionDef.timeoutMs());
        }

        var function = builder.build();

        if (!Modifier.isStatic(method.getModifiers())) {
            function.object = object;
        }

        return function;
    }

    private static List<ToolCallParameter> buildParameters(Method method) {
        var parameterList = new ArrayList<ToolCallParameter>();
        var methodParameters = method.getParameters();
        for (var methodParameter : methodParameters) {
            // skip ExecutionContext type, not included in tool parameter definition
            if (methodParameter.getType() == ExecutionContext.class) {
                continue;
            }
            var parameter = buildParameter(methodParameter);
            parameterList.add(parameter);
        }
        return parameterList;
    }

    private static ToolCallParameter buildParameter(Parameter methodParameter) {
        var functionParam = methodParameter.getAnnotation(CoreAiParameter.class);
        var parameter = new ToolCallParameter();
        parameter.setName(functionParam.name());
        parameter.setDescription(functionParam.description());
        parameter.setClassType(methodParameter.getType());

        // Extract generic type for collections/arrays (e.g., List<TodoEntity> -> TodoEntity.class)
        ToolCallParameterUtil.extractGenericItemType(methodParameter.getParameterizedType(), parameter);

        parameter.setRequired(functionParam.required());
        parameter.setEnums(List.of(functionParam.enums()));
        return parameter;
    }

    public static List<Method> getAllMethods(Class<?> clazz, Predicate<Method> predicate) {
        List<Method> methods = new ArrayList<>();
        buildMethods(clazz, methods, predicate, false);
        return methods;
    }


    private static void buildMethods(Class<?> clazz, List<Method> methods, Predicate<Method> predicate, boolean firstOnly) {
        if (clazz == null || clazz == Object.class) return;

        var declaredMethods = clazz.getDeclaredMethods();
        for (var method : declaredMethods) {
            if (predicate == null || predicate.test(method)) {
                methods.add(method);
                if (firstOnly) break;
            }
        }
        if (firstOnly && !methods.isEmpty()) return;

        buildMethods(clazz.getSuperclass(), methods, predicate, firstOnly);
    }
}
