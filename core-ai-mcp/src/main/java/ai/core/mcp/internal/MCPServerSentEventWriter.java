package ai.core.mcp.internal;

import core.framework.internal.json.JSONWriter;

/**
 * @author miller
 */
class MCPServerSentEventWriter<T> {
    private final JSONWriter<T> writer;
    //TODO validation
    //private final Validator<T> validator;

    MCPServerSentEventWriter(Class<T> eventClass) {
        writer = new JSONWriter<>(eventClass);
        //validator = Validator.of(eventClass);
    }

    String toMessage(String id, T event) {
        //validator.validate(event, false);
        String data = writer.toJSONString(event);

        return message(id, data);
    }

    String message(String id, String data) {
        var builder = new StringBuilder(data.length() + 7 + (id == null ? 0 : id.length() + 4));
        if (id != null) builder.append("id: ").append(id).append('\n');
        builder.append("data: ").append(data).append("\n\n");

        return builder.toString();
    }
}
