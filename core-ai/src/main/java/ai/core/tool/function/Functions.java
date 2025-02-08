package ai.core.tool.function;

import ai.core.tool.function.annotation.CoreAiMethod;
import ai.core.tool.function.converter.response.DefaultJsonResponseConverter;

import java.io.Serial;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

    public static Functions from(Object object) {
        return from(object.getClass(), object, getAllMethods(object.getClass(), method -> method.getAnnotation(CoreAiMethod.class) != null).stream().map(Method::getName).toArray(String[]::new));
    }

    public static Functions from(Object object, String... methodNames) {
        return from(object.getClass(), object, methodNames);
    }

    private static Functions from(Class<?> clazz, Object object, String... methodNames) {
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
            var function = new Function();
            function.setMethod(method);

            if (!Modifier.isStatic(method.getModifiers())) {
                function.object = object;
            }
            function.responseConverter = new DefaultJsonResponseConverter();
            functions.add(function);
        }

        return functions;
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
