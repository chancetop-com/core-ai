package ai.core.server.messaging;

import java.time.Duration;

/**
 * @author stephen
 */
public interface RpcTransport {
    /** @return the raw {@code {"ok":...}} response JSON. */
    String call(String targetPod, SessionCommand command, Duration timeout);
}
