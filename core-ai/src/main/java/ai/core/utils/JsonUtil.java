package ai.core.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import core.framework.internal.json.JSONAnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.PUBLIC_ONLY;

/**
 * @author stephen
 */
public class JsonUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setVisibility(new VisibilityChecker.Std(NONE, NONE, NONE, NONE, PUBLIC_ONLY))
            .setAnnotationIntrospector(new JSONAnnotationIntrospector())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static String toJson(Object instance) {
        if (instance == null) {
            throw new Error("instance must not be null");
        } else {
            try {
                return OBJECT_MAPPER.writeValueAsString(instance);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static <T> T fromJson(Class<T> instanceClass, String json) {
        try {
            T result = OBJECT_MAPPER.readValue(json, instanceClass);
            if (result == null) {
                throw new Error("invalid json value, value=" + json);
            } else {
                return result;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T fromJson(Class<T> instanceClass, Map<?, ?> map) {
        T result = OBJECT_MAPPER.convertValue(map, instanceClass);
        if (result == null) {
            throw new Error("invalid json value, value=" + map);
        } else {
            return result;
        }
    }

    public static <T> T fromJson(Class<T> instanceClass, Object obj) {
        if (obj == null) {
            throw new Error("object must not be null");
        }
        try {
            T result = OBJECT_MAPPER.convertValue(obj, instanceClass);
            if (result == null) {
                throw new Error("invalid json value, value=" + obj);
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new Error("failed to convert object to " + instanceClass.getName() + ", value=" + obj, e);
        }
    }


    @SuppressWarnings("rawtypes")
    public static Map toMap(Object instance) {
        if (instance == null) throw new Error("instance must not be null");
        return OBJECT_MAPPER.convertValue(instance, Map.class);
    }
}
