package ai.core.sse;

import core.framework.web.sse.Channel;

/**
* author cyril
* description
* createTime  2026/6/10
**/
public interface RawSseChannel<T> extends Channel<T> {

    /**
     * Sends raw SSE data without JSON serialization.
     * The data is wrapped as {@code data: <data>\n\n} and written
     * directly to the Undertow stream.
     *
     * @param data raw SSE data (not including SSE framing prefix/suffix)
     * @return true if queued, false if channel is closed
     */
    boolean sendRawData(String data);

    /**
     * Sends a raw SSE event with an explicit event name, wrapped as
     * {@code event: <event>\ndata: <data>\n\n}. Falls back to {@link #sendRawData}
     * when the event name is null or blank.
     *
     * @param event SSE event name (may be null)
     * @param data  raw SSE data (not including SSE framing prefix/suffix)
     * @return true if queued, false if channel is closed
     */
    boolean sendRawEvent(String event, String data);

    /**
     * Sends a raw SSE event with an explicit id and event name, wrapped as
     * {@code id: <id>\nevent: <event>\ndata: <data>\n\n}. This is needed for
     * Last-Event-ID resume: a bridged upstream event's own sequence id must
     * reach the browser unchanged so a client reconnect can resume from it.
     * <p>
     * Default implementation delegates to {@link #sendRawEvent(String, String)}
     * (dropping the id) so any existing implementer keeps compiling and
     * behaving exactly as before; an implementer that wants real id framing
     * must override this method (see {@code PatchedChannelImpl}).
     *
     * @param id    SSE event id (may be null or blank, in which case the id line is omitted)
     * @param event SSE event name (may be null)
     * @param data  raw SSE data (not including SSE framing prefix/suffix)
     * @return true if queued, false if channel is closed
     */
    default boolean sendRawEvent(String id, String event, String data) {
        return sendRawEvent(event, data);
    }
}
