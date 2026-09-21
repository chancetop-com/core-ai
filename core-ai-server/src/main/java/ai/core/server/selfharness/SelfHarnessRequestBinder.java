package ai.core.server.selfharness;

import core.framework.api.json.Property;
import core.framework.api.web.service.QueryParam;
import core.framework.json.JSON;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * Binds tool-call arguments to a request DTO by the parameter names the tool advertises:
 * {@code @Property}, else {@code @QueryParam}, else the field name.
 * <p>
 * Deserializing the DTO straight from the arguments only understands {@code @Property} and
 * field names, so query-param names such as {@code q} or {@code search_in} silently arrived
 * as null — the tool accepted the argument and ignored it.
 *
 * @author stephen
 */
public final class SelfHarnessRequestBinder {
    @SuppressWarnings("unchecked")
    public static <T> T bind(Class<T> requestType, String args) {
        Map<String, Object> values = JSON.fromJSON(Map.class, args);
        T request = newInstance(requestType);
        for (Field field : requestType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            var value = values.get(parameterName(field));
            if (value != null) {
                setField(request, field, value);
            }
        }
        return request;
    }

    private static String parameterName(Field field) {
        var property = field.getAnnotation(Property.class);
        if (property != null) return property.name();
        var queryParam = field.getAnnotation(QueryParam.class);
        if (queryParam != null) return queryParam.name();
        return field.getName();
    }

    private static <T> T newInstance(Class<T> requestType) {
        try {
            return requestType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to create self-harness request bean, class=" + requestType.getName(), e);
        }
    }

    private static void setField(Object request, Field field, Object value) {
        try {
            field.set(request, JSON.fromJSON(field.getGenericType(), JSON.toJSON(value)));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("failed to set self-harness request field, field=" + field.getName(), e);
        }
    }

    private SelfHarnessRequestBinder() {
    }
}
