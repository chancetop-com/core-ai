package ai.core.server.replay.service;

import ai.core.llm.domain.CompletionRequest;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes ChatML request JSON into {@link CompletionRequest}.
 * <p>
 * The project's JsonUtil mapper (core-ng {@code JSONAnnotationIntrospector}) does not
 * honor Jackson's {@code @JsonDeserialize}, so {@code Message.ContentListDeserializer}
 * never runs and plain string content ({@code "content": "hi"}) cannot deserialize into
 * {@code List<Content>} — the most common span input shape. Normalizing string content to
 * the array form {@code [{"type":"text","text":...}]} before deserialization is
 * semantically identical for upstream providers.
 *
 * @author stephen
 */
final class ReplayRequestCodec {
    static CompletionRequest parse(String chatmlJson) {
        return JsonUtil.fromJson(CompletionRequest.class, normalize(chatmlJson));
    }

    static String normalize(String chatmlJson) {
        var root = JsonUtil.toMap(chatmlJson);
        var messages = root.get("messages");
        if (messages instanceof List<?> list) {
            for (var item : list) {
                if (!(item instanceof Map<?, ?> message)) continue;
                var content = message.get("content");
                if (content instanceof String text) {
                    @SuppressWarnings("unchecked")
                    var mutable = (Map<String, Object>) message;
                    var textPart = new LinkedHashMap<String, Object>();
                    textPart.put("type", "text");
                    textPart.put("text", text);
                    mutable.put("content", List.of(textPart));
                }
            }
        }
        return JsonUtil.toJson(root);
    }

    private ReplayRequestCodec() {
    }
}
