package ai.core.sse.internal;

import ai.core.utils.JsonUtil;
import org.jspecify.annotations.Nullable;

class PatchedServerSentEventWriter<T> {
    // todo: no validator, use jsonutils directly
//    private final JSONWriter<T> writer;
//    private final Validator<T> validator;

    PatchedServerSentEventWriter(Class<T> eventClass) {
//        writer = new JSONWriter<>(eventClass);
//        validator = Validator.of(eventClass);
    }

    String toMessage(@Nullable String id, T event) {
        // todo: no validator
//        validator.validate(event, false);
//        String data = writer.toJSONString(event);
        var data = JsonUtil.toJson(event);
        return message(id, data);
    }

    String message(@Nullable String id, String data) {
        var builder = new StringBuilder(data.length() + 7 + (id == null ? 0 : id.length() + 4));
        if (id != null) builder.append("id: ").append(id).append('\n');
        builder.append("data: ").append(data).append("\n\n");

        return builder.toString();
    }
}
