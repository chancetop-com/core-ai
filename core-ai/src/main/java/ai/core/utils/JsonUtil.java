package ai.core.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import core.framework.internal.json.JSONAnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.PUBLIC_ONLY;

/**
 * @author stephen
 */
public class JsonUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonUtil.class);

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL).setVisibility(new VisibilityChecker.Std(NONE, NONE, NONE, NONE, PUBLIC_ONLY)).setAnnotationIntrospector(new JSONAnnotationIntrospector()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
    public static <T> T fromJson(Type instanceType, String json) {
        try {
            JavaType javaType = OBJECT_MAPPER.getTypeFactory().constructType(instanceType);
            T result = OBJECT_MAPPER.readValue(json, javaType);
            if (result == null)
                throw new Error("invalid json value, value=" + json);   // not allow passing "null" as json value
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    public static <T> T fromJsonSafe(Class<T> instanceClass, String json) {
        if (json == null) {
            throw new Error("json must not be null");
        }
        try {
            T result = OBJECT_MAPPER.readValue(json, instanceClass);
            if (result != null) {
                return result;
            }
        } catch (IOException ignore) {
            LOGGER.warn("failed to parse json, try to parse as double-encoded json, value={}", json);
            // parse second time
        }

        try {
            var inner = OBJECT_MAPPER.readValue(json, String.class);
            T result = OBJECT_MAPPER.readValue(inner, instanceClass);
            if (result == null) {
                throw new Error("invalid double-encoded json value, value=" + json);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse json safely, value=" + json, e);
        }
    }

    @SuppressWarnings("rawtypes")
    public static Map toMap(Object instance) {
        if (instance == null) throw new Error("instance must not be null");
        return OBJECT_MAPPER.convertValue(instance, Map.class);
    }
}
