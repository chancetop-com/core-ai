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
}
