package ai.core.sse.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import core.framework.internal.json.JSONAnnotationIntrospector;

import java.io.UncheckedIOException;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.PUBLIC_ONLY;

/**
 * @author miller
 */
class PatchedServerSentEventWriter<T> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setVisibility(new VisibilityChecker.Std(NONE, NONE, NONE, NONE, PUBLIC_ONLY))
            .setAnnotationIntrospector(new JSONAnnotationIntrospector());

    String toMessage(String id, T event) {
        //validator.validate(event, false);
        try {
            String data = OBJECT_MAPPER.writeValueAsString(event);
            return message(id, data);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    String message(String id, String data) {
        var builder = new StringBuilder(data.length() + 7 + (id == null ? 0 : id.length() + 4));
        if (id != null) builder.append("id: ").append(id).append('\n');
        builder.append("data: ").append(data).append("\n\n");

        return builder.toString();
    }
}
