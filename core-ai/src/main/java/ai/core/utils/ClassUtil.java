package ai.core.utils;

import core.framework.util.Strings;

import java.lang.reflect.InvocationTargetException;

/**
 * @author stephen
 */
public class ClassUtil {
    public static void checkNoArgConstructor(Class<?> type) {
        boolean hasNoArg = false;
        for (var c : type.getDeclaredConstructors()) {
            if (c.getParameterCount() == 0) {
                hasNoArg = true;
                break;
            }
        }
        if (!hasNoArg) {
            throw new IllegalStateException(Strings.format("Class {}'s must has no arg constructor", type.getName()));
        }
    }

    public static Object newByName(String className) {
        try {
            return Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException
                 | NoSuchMethodException
                 | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
