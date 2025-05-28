package ai.core.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import core.framework.internal.json.JSONAnnotationIntrospector;

import java.io.IOException;
import java.io.UncheckedIOException;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.PUBLIC_ONLY;

/**
 * @author stephen
 */
public class JsonUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setVisibility(new VisibilityChecker.Std(NONE, NONE, NONE, NONE, PUBLIC_ONLY))
            .setAnnotationIntrospector(new JSONAnnotationIntrospector());

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
}
