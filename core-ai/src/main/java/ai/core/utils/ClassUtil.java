package ai.core.utils;

import core.framework.util.Strings;

import java.lang.reflect.InvocationTargetException;

/**
 * @author stephen
 */
public class ClassUtil {
    public static void checkNoArgConstructor(Class<?> type) {
        try {
            type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(Strings.format("Class {}'s must has no arg constructor", type.getName()), e);
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
